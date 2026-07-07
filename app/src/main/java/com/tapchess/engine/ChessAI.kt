package com.tapchess.engine

import kotlin.math.abs
import kotlin.random.Random

/**
 * Difficulty tiers. Depth + quiescence set strength; `blunder` gives the easy
 * tiers a chance to pick a near-best (or random) move so beginners can win.
 */
enum class Difficulty(
    val label: String, val depth: Int, val quiesce: Boolean,
    val blunderChance: Float, val poolMargin: Int, val budgetMs: Long,
) {
    PAWN("1 · Pawn", 1, false, 0.55f, 220, 900),
    KNIGHT("2 · Knight", 2, false, 0.28f, 130, 1200),
    BISHOP("3 · Bishop", 3, false, 0.10f, 70, 1800),
    ROOK("4 · Rook", 3, true, 0.0f, 0, 2200),
    QUEEN("5 · Queen", 4, true, 0.0f, 0, 2600);

    companion object {
        fun from(i: Int) = entries[i.coerceIn(0, entries.size - 1)]
    }
}

class ChessAI {
    @Volatile private var deadline = 0L
    @Volatile private var aborted = false
    private val rng = Random(System.nanoTime())

    private val value = intArrayOf(0, 100, 320, 330, 500, 900, 0)

    fun bestMove(board: Board, diff: Difficulty): Move? {
        val legal = board.generateLegal()
        if (legal.isEmpty()) return null
        if (legal.size == 1) return legal[0]
        deadline = System.currentTimeMillis() + diff.budgetMs
        aborted = false

        var scored = legal.map { it to 0 }
        for (d in 1..diff.depth) {
            val ordered = scored.sortedByDescending { it.second }.map { it.first }
            val res = ArrayList<Pair<Move, Int>>(ordered.size)
            for (m in ordered) {
                if (System.currentTimeMillis() > deadline) { aborted = true; break }
                val s = -search(board.applied(m), d - 1, -INF, INF, 1, diff)
                res.add(m to s)
            }
            if (!aborted && res.isNotEmpty()) scored = res
            if (aborted) break
            if (scored.any { abs(it.second) > MATE - 100 }) break
        }

        val best = scored.maxByOrNull { it.second } ?: return legal.random(rng)
        if (diff.blunderChance > 0f && rng.nextFloat() < diff.blunderChance) {
            val pool = scored.filter { best.second - it.second <= diff.poolMargin }
            return (if (pool.isNotEmpty()) pool else scored).random(rng).first
        }
        // Pick randomly among exactly-tied best moves for variety.
        val tied = scored.filter { it.second == best.second }
        return tied.random(rng).first
    }

    private fun search(board: Board, depth: Int, a0: Int, beta: Int, ply: Int, diff: Difficulty): Int {
        if (aborted || System.currentTimeMillis() > deadline) { aborted = true; return 0 }
        var alpha = a0
        val moves = board.generateLegal()
        if (moves.isEmpty()) {
            return if (board.inCheck(board.whiteToMove())) -(MATE - ply) else 0
        }
        if (board.insufficientMaterial()) return 0
        if (depth == 0) return if (diff.quiesce) quiesce(board, alpha, beta, ply, diff) else evalNega(board)

        order(board, moves)
        var best = -INF
        for (m in moves) {
            val s = -search(board.applied(m), depth - 1, -beta, -alpha, ply + 1, diff)
            if (s > best) best = s
            if (best > alpha) alpha = best
            if (alpha >= beta) break
        }
        return best
    }

    private fun quiesce(board: Board, a0: Int, beta: Int, ply: Int, diff: Difficulty): Int {
        if (aborted || System.currentTimeMillis() > deadline) { aborted = true; return 0 }
        var alpha = a0
        val stand = evalNega(board)
        if (stand >= beta) return beta
        if (stand > alpha) alpha = stand
        if (ply > 12) return alpha
        val caps = board.generateLegal().filter { board.sq[it.to] != 0 || it.flag == F_ENPASSANT }
        order(board, caps)
        for (m in caps) {
            val s = -quiesce(board.applied(m), -beta, -alpha, ply + 1, diff)
            if (s >= beta) return beta
            if (s > alpha) alpha = s
        }
        return alpha
    }

    /** MVV-LVA-ish: try captures of big pieces by small pieces first. */
    private fun order(board: Board, moves: List<Move>) {
        (moves as? ArrayList<Move>)?.sortByDescending { m ->
            val victim = abs(board.sq[m.to])
            val attacker = abs(board.sq[m.from])
            if (victim > 0) victim * 10 - attacker + 1000 else 0
        }
    }

    /** Static eval from the side-to-move's perspective (negamax convention). */
    private fun evalNega(board: Board): Int {
        var score = 0
        for (i in 0 until 64) {
            val p = board.sq[i]
            if (p == 0) continue
            val t = abs(p)
            val pst = PST[t]
            if (p > 0) score += value[t] + pst[i]
            else score -= value[t] + pst[i xor 56] // mirror rank for black
        }
        return score * board.side
    }

    companion object {
        private const val INF = 1_000_000
        private const val MATE = 30_000

        // Compact midgame piece-square tables (White's view, a1=0..h8=63).
        private val PST_PAWN = intArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0,
            5, 10, 10, -20, -20, 10, 10, 5,
            5, -5, -10, 0, 0, -10, -5, 5,
            0, 0, 0, 20, 20, 0, 0, 0,
            5, 5, 10, 25, 25, 10, 5, 5,
            10, 10, 20, 30, 30, 20, 10, 10,
            50, 50, 50, 50, 50, 50, 50, 50,
            0, 0, 0, 0, 0, 0, 0, 0
        )
        private val PST_KNIGHT = intArrayOf(
            -50, -40, -30, -30, -30, -30, -40, -50,
            -40, -20, 0, 5, 5, 0, -20, -40,
            -30, 5, 10, 15, 15, 10, 5, -30,
            -30, 0, 15, 20, 20, 15, 0, -30,
            -30, 5, 15, 20, 20, 15, 5, -30,
            -30, 0, 10, 15, 15, 10, 0, -30,
            -40, -20, 0, 0, 0, 0, -20, -40,
            -50, -40, -30, -30, -30, -30, -40, -50
        )
        private val PST_BISHOP = intArrayOf(
            -20, -10, -10, -10, -10, -10, -10, -20,
            -10, 5, 0, 0, 0, 0, 5, -10,
            -10, 10, 10, 10, 10, 10, 10, -10,
            -10, 0, 10, 10, 10, 10, 0, -10,
            -10, 5, 5, 10, 10, 5, 5, -10,
            -10, 0, 5, 10, 10, 5, 0, -10,
            -10, 0, 0, 0, 0, 0, 0, -10,
            -20, -10, -10, -10, -10, -10, -10, -20
        )
        private val PST_ROOK = intArrayOf(
            0, 0, 0, 5, 5, 0, 0, 0,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            5, 10, 10, 10, 10, 10, 10, 5,
            0, 0, 0, 0, 0, 0, 0, 0
        )
        private val PST_QUEEN = intArrayOf(
            -20, -10, -10, -5, -5, -10, -10, -20,
            -10, 0, 5, 0, 0, 0, 0, -10,
            -10, 5, 5, 5, 5, 5, 0, -10,
            0, 0, 5, 5, 5, 5, 0, -5,
            -5, 0, 5, 5, 5, 5, 0, -5,
            -10, 0, 5, 5, 5, 5, 0, -10,
            -10, 0, 0, 0, 0, 0, 0, -10,
            -20, -10, -10, -5, -5, -10, -10, -20
        )
        private val PST_KING = intArrayOf(
            20, 30, 10, 0, 0, 10, 30, 20,
            20, 20, 0, 0, 0, 0, 20, 20,
            -10, -20, -20, -20, -20, -20, -20, -10,
            -20, -30, -30, -40, -40, -30, -30, -20,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30
        )
        private val PST_EMPTY = IntArray(64)
        private val PST = arrayOf(PST_EMPTY, PST_PAWN, PST_KNIGHT, PST_BISHOP, PST_ROOK, PST_QUEEN, PST_KING)
    }
}
