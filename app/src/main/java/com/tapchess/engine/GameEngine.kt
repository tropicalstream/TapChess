package com.tapchess.engine

import com.tapchess.SettingsStore
import com.tapchess.audio.Audio
import kotlin.math.abs
import kotlin.math.max

enum class GameState { MENU, PLAYING, THINKING, PROMOTION, OVER }

interface GameHost {
    fun applySettings()
    fun sound(id: Int, pitch: Float = 1f, vol: Float = 1f)
}

/**
 * Drives a game of chess against [ChessAI]. Human moves come from a swipe-driven
 * cursor + click; illegal attempts are explained rather than silently rejected.
 * The AI runs on a background thread and posts its move back through a volatile
 * that update() consumes on the main thread (guarded by a generation counter so
 * undo / new-game cancel a stale reply).
 */
class GameEngine(val store: SettingsStore, val host: GameHost) {

    var state = GameState.MENU
        private set
    var settingsOpen = false
        private set
    val settingsMenu = SettingsMenu(this, store)

    val particles = ParticleSystem()

    var board = Board.initial()
        private set
    var humanWhite = true
        private set
    var difficulty = Difficulty.KNIGHT
        private set

    var cursor = 12
    var selected = -1
        private set
    var targets = HashSet<Int>()
        private set
    var lastFrom = -1
        private set
    var lastTo = -1
        private set

    // Opponent's most recent move, shown as a dotted trail on the player's turn.
    var oppFrom = -1
        private set
    var oppTo = -1
        private set

    // Dwell-to-commit: the cursor must rest on the destination for [DWELL]
    // seconds before a tap will move there — a guard against the X3 pad
    // registering a finicky swipe as the wrong square.
    var cursorSince = 0f
        private set
    val dwellProgress get() = ((time - cursorSince) / DWELL).coerceIn(0f, 1f)
    val moveArmed get() = time - cursorSince >= DWELL

    var invalidMsg: String? = null
        private set
    var invalidIsHint = false
        private set
    var invalidT = 0f
    var statusMsg = ""
        private set
    var resultMsg = ""
        private set
    var checkSquare = -1
        private set

    // Speed mode.
    var speedOn = false
        private set
    var whiteMs = 0L
        private set
    var blackMs = 0L
        private set

    // Promotion picker.
    var promoFrom = -1
    var promoTo = -1
    var promoIdx = 0
    val promoPieces = intArrayOf(P.QUEEN, P.ROOK, P.BISHOP, P.KNIGHT)

    var time = 0f
    var menuDiff = 1
    var thinkPulse = 0f

    private val history = ArrayList<Board>()
    private val ai = ChessAI()
    private var aiGen = 0
    @Volatile private var pendingMove: Move? = null
    @Volatile private var pendingGen = -1

    val flip get() = !humanWhite   // board drawn from the human's side

    fun boot() {
        menuDiff = store.difficulty
        toMenu()
    }

    // --------------------------------------------------------------- loop

    fun update(dt: Float) {
        time += dt
        thinkPulse = (thinkPulse + dt) % 1f
        particles.update(dt)
        if (invalidT > 0f) { invalidT -= dt; if (invalidT <= 0f) invalidMsg = null }
        if (settingsOpen) return

        // AI result hand-off (computed off-thread).
        val pm = pendingMove
        if (pm != null && pendingGen == aiGen && state == GameState.THINKING) {
            pendingMove = null
            history.add(board.clone())
            doMove(pm)
            oppFrom = pm.from   // show the opponent's move as a dotted trail
            oppTo = pm.to
            if (state == GameState.THINKING) state = GameState.PLAYING
        }

        if (speedOn && (state == GameState.PLAYING || state == GameState.THINKING)) {
            val white = board.whiteToMove()
            if (white) whiteMs = max(0L, whiteMs - (dt * 1000).toLong())
            else blackMs = max(0L, blackMs - (dt * 1000).toLong())
            val low = if (white) whiteMs else blackMs
            if (low in 1..10000 && time % 1f < dt) host.sound(Audio.LOWTIME, 1.2f, 0.6f)
            if ((white && whiteMs <= 0L) || (!white && blackMs <= 0L)) flagFall(white)
        }
    }

    private fun flagFall(whiteFlagged: Boolean) {
        val humanFlagged = whiteFlagged == humanWhite
        endGame(if (humanFlagged) "Out of time — you lose." else "Your opponent flagged. You win!",
            humanWon = !humanFlagged)
    }

    // ------------------------------------------------------------- input

    fun cursorMove(dir: Int) {
        if (state != GameState.PLAYING) return
        var f = file(cursor); var r = rank(cursor)
        // dir: 0 up, 1 down, 2 left, 3 right (visual). Map to board coords.
        when (dir) {
            0 -> if (!flip) r++ else r--
            1 -> if (!flip) r-- else r++
            2 -> if (!flip) f-- else f++
            3 -> if (!flip) f++ else f--
        }
        if (onBoard(f, r)) {
            cursor = sqOf(f, r)
            cursorSince = time // restart the dwell timer on every hop
            host.sound(Audio.TICK, 1.4f, 0.5f)
        }
    }

    fun click() {
        when {
            settingsOpen -> settingsMenu.activate()
            state == GameState.MENU -> startGame()
            state == GameState.PROMOTION -> confirmPromo()
            state == GameState.PLAYING -> boardClick()
            state == GameState.OVER -> toMenu()
            else -> {}
        }
    }

    private fun boardClick() {
        if (board.whiteToMove() != humanWhite) return // not your turn
        val p = board.sq[cursor]
        if (selected == -1) {
            when {
                p != 0 && (p > 0) == humanWhite -> selectSquare(cursor)
                p != 0 -> setInvalid("That's your opponent's piece — tap one of yours.")
                else -> setInvalid("No piece there. Tap one of your own pieces first.")
            }
            return
        }
        if (cursor == selected) { deselect(); return }
        if (p != 0 && (p > 0) == humanWhite) { selectSquare(cursor); return }
        when (val out = board.classify(selected, cursor)) {
            is MoveOutcome.Legal -> {
                // Legal, but require the cursor to have settled here first so a
                // stray swipe can't fling a piece to the wrong square.
                if (!moveArmed) {
                    setHint("Rest on the square a moment, then tap to move.")
                    host.sound(Audio.TICK, 0.9f, 0.6f)
                    return
                }
                val m = out.moves[0]
                if (m.flag == F_PROMO) {
                    promoFrom = selected; promoTo = cursor; promoIdx = 0
                    state = GameState.PROMOTION
                    host.sound(Audio.SELECT)
                } else applyHuman(m)
            }
            is MoveOutcome.Illegal -> {
                // Illegal feedback is instant and informative (no dwell needed).
                setInvalid(out.reason)
                host.sound(Audio.ILLEGAL)
            }
        }
    }

    private fun selectSquare(sq: Int) {
        selected = sq
        targets = HashSet(board.generateLegal().filter { it.from == sq }.map { it.to })
        invalidMsg = null
        cursorSince = time // dwell starts fresh from the piece's own square
        host.sound(Audio.SELECT)
    }

    private fun deselect() {
        selected = -1
        targets = HashSet()
    }

    private fun confirmPromo() {
        val promo = promoPieces[promoIdx]
        val m = board.generateLegal().firstOrNull {
            it.from == promoFrom && it.to == promoTo && it.promo == promo
        }
        state = GameState.PLAYING
        if (m != null) applyHuman(m)
    }

    private fun applyHuman(m: Move) {
        history.add(board.clone())
        oppFrom = -1; oppTo = -1 // player has responded; clear the opponent trail
        deselect()
        doMove(m)
        if (state == GameState.PLAYING || state == GameState.PROMOTION) {
            if (!isGameOver()) { state = GameState.THINKING; requestAi() }
        }
    }

    // --------------------------------------------------------------- move

    private fun doMove(m: Move) {
        val capture = board.sq[m.to] != 0 || m.flag == F_ENPASSANT
        val toSq = m.to
        board = board.applied(m)
        lastFrom = m.from; lastTo = m.to
        cursor = m.to
        cursorSince = time // fresh dwell required for the next move
        when {
            m.flag == F_CASTLE -> host.sound(Audio.CASTLE)
            capture -> {
                host.sound(Audio.CAPTURE)
                particles.burst(sqCenterX(toSq), sqCenterY(toSq), 0xFFFFC060.toInt(), 1f)
            }
            else -> host.sound(Audio.MOVE)
        }
        evaluatePosition()
    }

    private fun evaluatePosition() {
        val stm = board.whiteToMove()
        val legal = board.generateLegal()
        checkSquare = if (board.inCheck(stm)) board.kingSquare(stm) else -1
        when {
            legal.isEmpty() && board.inCheck(stm) -> {
                val humanWon = stm != humanWhite
                endGame(if (humanWon) "Checkmate — you win!" else "Checkmate — you lose.", humanWon)
            }
            legal.isEmpty() -> endGame("Stalemate — it's a draw.", null)
            board.insufficientMaterial() -> endGame("Draw — insufficient material.", null)
            board.halfmove >= 100 -> endGame("Draw — 50-move rule.", null)
            board.inCheck(stm) -> { statusMsg = "Check!"; host.sound(Audio.CHECK) }
            else -> statusMsg = ""
        }
    }

    private fun isGameOver() = state == GameState.OVER

    private fun endGame(msg: String, humanWon: Boolean?) {
        resultMsg = msg
        statusMsg = ""
        state = GameState.OVER
        aiGen++ // cancel any pending AI
        when (humanWon) {
            true -> { store.wins++; host.sound(Audio.WIN) }
            false -> { store.losses++; host.sound(Audio.LOSE) }
            null -> { store.draws++; host.sound(Audio.CHECK, 0.8f) }
        }
    }

    // ----------------------------------------------------------- AI hook

    private fun requestAi() {
        aiGen++
        val myGen = aiGen
        val snapshot = board.clone()
        val diff = difficulty
        Thread {
            val mv = ai.bestMove(snapshot, diff)
            if (mv != null) { pendingMove = mv; pendingGen = myGen }
        }.start()
    }

    // -------------------------------------------------------- navigation

    fun startGame() {
        humanWhite = store.playerWhite
        difficulty = Difficulty.from(store.difficulty)
        board = Board.initial()
        history.clear()
        deselect()
        lastFrom = -1; lastTo = -1
        oppFrom = -1; oppTo = -1
        checkSquare = -1
        statusMsg = ""; resultMsg = ""; invalidMsg = null
        cursor = if (humanWhite) 12 else 52
        cursorSince = time
        particles.clear()
        speedOn = store.speedSeconds > 0
        whiteMs = store.speedSeconds * 1000L
        blackMs = store.speedSeconds * 1000L
        settingsOpen = false
        aiGen++
        pendingMove = null
        state = GameState.PLAYING
        host.sound(Audio.SELECT)
        if (board.whiteToMove() != humanWhite) { state = GameState.THINKING; requestAi() }
    }

    fun toMenu() {
        state = GameState.MENU
        settingsOpen = false
        aiGen++
        pendingMove = null
        particles.clear()
        menuDiff = store.difficulty
    }

    fun newGame() = startGame()
    fun restart() = startGame()

    fun undo() {
        if (history.isEmpty() || state == GameState.MENU) return
        aiGen++
        pendingMove = null
        var pops = 0
        while (history.isNotEmpty() && pops < 2) {
            board = history.removeAt(history.size - 1)
            pops++
            if (board.whiteToMove() == humanWhite) break
        }
        deselect()
        lastFrom = -1; lastTo = -1
        oppFrom = -1; oppTo = -1
        cursorSince = time
        resultMsg = ""
        state = GameState.PLAYING
        checkSquare = if (board.inCheck(board.whiteToMove())) board.kingSquare(board.whiteToMove()) else -1
        statusMsg = if (checkSquare >= 0) "Check!" else ""
        host.sound(Audio.TICK)
    }

    fun resign() {
        if (state == GameState.PLAYING || state == GameState.THINKING || state == GameState.PROMOTION) {
            endGame("You resigned.", humanWon = false)
        }
    }

    // -------------------------------------------------------- settings/UI

    fun doubleTap() {
        settingsOpen = !settingsOpen
        if (settingsOpen) settingsMenu.onOpen()
        host.sound(if (settingsOpen) Audio.SELECT else Audio.TICK)
    }

    fun onBack(): Boolean {
        if (settingsOpen) { doubleTap(); return true }
        return when (state) {
            GameState.PLAYING, GameState.THINKING, GameState.PROMOTION -> { doubleTap(); true }
            GameState.OVER -> { toMenu(); true }
            else -> false
        }
    }

    /**
     * One discrete swipe gesture from the Activity, classified on finger-up.
     * dir: 0 up, 1 down, 2 left, 3 right. Every context — settings, menu,
     * board, promotion — is one-gesture-one-step, so nothing accumulates or
     * lags (the fix for the finicky settings navigation).
     */
    fun swipeDir(dir: Int) {
        if (settingsOpen) { settingsMenu.onDir(dir); return }
        when (state) {
            GameState.PLAYING -> cursorMove(dir)
            GameState.PROMOTION -> if (dir == 2 || dir == 3) {
                promoIdx = (promoIdx + (if (dir == 3) 1 else -1) + promoPieces.size) % promoPieces.size
                host.sound(Audio.TICK)
            }
            GameState.MENU -> if (dir == 2 || dir == 3) {
                menuDiff = (menuDiff + (if (dir == 3) 1 else -1)).coerceIn(0, 4)
                store.difficulty = menuDiff
                host.sound(Audio.TICK)
            }
            else -> {}
        }
    }

    fun onAppPause() {
        if ((state == GameState.PLAYING || state == GameState.THINKING) && !settingsOpen) {
            settingsOpen = true
            settingsMenu.onOpen()
        }
    }

    private fun setInvalid(msg: String) {
        invalidMsg = msg
        invalidIsHint = false
        invalidT = 2.6f
    }

    private fun setHint(msg: String) {
        invalidMsg = msg
        invalidIsHint = true
        invalidT = 1.6f
    }

    // --------------------------------------------------- board geometry

    companion object {
        const val BOARD_X = 144f
        const val BOARD_Y = 64f
        const val SQ = 44f
        const val DWELL = 2f // seconds the cursor must rest before a move commits
    }

    /** Screen column/row (0..7, row 0 = top) for a board square, honoring flip. */
    fun col(sq: Int) = if (!flip) file(sq) else 7 - file(sq)
    fun row(sq: Int) = if (!flip) 7 - rank(sq) else rank(sq)
    fun sqCenterX(sq: Int) = BOARD_X + col(sq) * SQ + SQ / 2
    fun sqCenterY(sq: Int) = BOARD_Y + row(sq) * SQ + SQ / 2
}
