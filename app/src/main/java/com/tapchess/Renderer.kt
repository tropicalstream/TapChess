package com.tapchess

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.tapchess.engine.Board
import com.tapchess.engine.Difficulty
import com.tapchess.engine.GameEngine
import com.tapchess.engine.GameState
import com.tapchess.engine.P
import com.tapchess.engine.file
import com.tapchess.engine.rank
import com.tapchess.engine.sqOf
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** All drawing in 640x480 logical space on pure black (waveguide = black transparent). */
class Renderer(private val engine: GameEngine, private val store: SettingsStore) {

    private val W = 640f
    private val H = 480f
    private val BX = GameEngine.BOARD_X
    private val BY = GameEngine.BOARD_Y
    private val SQ = GameEngine.SQ

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val rf = RectF()
    private val piecePath = Path()

    private val lightSq = 0xFF33456A.toInt()
    private val darkSq = 0xFF1B2740.toInt()
    private val whiteFill = 0xFFEDEFF6.toInt()
    private val whiteRim = 0xFF2C3E5E.toInt()
    private val blackFill = 0xFF2A3247.toInt()
    private val blackRim = 0xFFBBC6DA.toInt()

    fun draw(c: Canvas, w: Int, h: Int) {
        c.drawColor(Color.BLACK)
        if (w <= 0 || h <= 0) return
        val s = min(w / W, h / H)
        c.save()
        c.translate((w - W * s) / 2f, (h - H * s) / 2f)
        c.scale(s, s)

        if (engine.state == GameState.MENU) drawMenu(c)
        else {
            drawBoard(c)
            drawPieces(c)
            drawOppTrail(c)
            drawHud(c)
            drawParticles(c)
            when (engine.state) {
                GameState.PROMOTION -> drawPromo(c)
                GameState.OVER -> drawOver(c)
                else -> {}
            }
            drawInvalid(c)
        }
        if (engine.settingsOpen) drawSettings(c)
        c.restore()
    }

    // ------------------------------------------------------------- board

    private fun drawBoard(c: Canvas) {
        rf.set(BX - 8f, BY - 8f, BX + 8 * SQ + 8f, BY + 8 * SQ + 8f)
        stroke.strokeWidth = 6f
        stroke.color = Color.argb(60, 100, 150, 220)
        c.drawRoundRect(rf, 8f, 8f, stroke)
        stroke.strokeWidth = 2f
        stroke.color = Color.argb(200, 140, 190, 245)
        c.drawRoundRect(rf, 8f, 8f, stroke)

        for (sqi in 0 until 64) {
            val col = engine.col(sqi); val row = engine.row(sqi)
            val x = BX + col * SQ; val y = BY + row * SQ
            val light = (file(sqi) + rank(sqi)) % 2 == 1
            fill.shader = null
            fill.color = if (light) lightSq else darkSq
            c.drawRect(x, y, x + SQ, y + SQ, fill)
        }

        // Last move highlight.
        if (engine.lastFrom >= 0) {
            highlightSquare(c, engine.lastFrom, Color.argb(70, 255, 220, 90))
            highlightSquare(c, engine.lastTo, Color.argb(90, 255, 220, 90))
        }
        // Check square.
        if (engine.checkSquare >= 0) {
            val a = (120 + 100 * sin(engine.time * 7f)).toInt().coerceIn(40, 255)
            highlightSquare(c, engine.checkSquare, Color.argb(a, 255, 60, 60))
        }
        // Selection + legal targets.
        if (engine.selected >= 0) {
            highlightSquare(c, engine.selected, Color.argb(110, 90, 230, 120))
            if (store.showLegal) {
                for (t in engine.targets) {
                    val cx = engine.sqCenterX(t); val cy = engine.sqCenterY(t)
                    fill.shader = null
                    if (engine.board.sq[t] != 0) {
                        stroke.strokeWidth = 3f
                        stroke.color = Color.argb(180, 90, 230, 120)
                        c.drawCircle(cx, cy, SQ * 0.42f, stroke)
                    } else {
                        fill.color = Color.argb(150, 90, 230, 120)
                        c.drawCircle(cx, cy, 6f, fill)
                    }
                }
            }
        }
        // Coordinates.
        if (store.showCoords) {
            for (i in 0 until 8) {
                val fileChar = ('a' + if (!engine.flip) i else 7 - i)
                text(c, fileChar.toString(), BX + i * SQ + SQ - 7f, BY + 8 * SQ + 11f, 9f, Color.argb(150, 150, 180, 220))
                val rankChar = (if (!engine.flip) 8 - i else i + 1).toString()
                text(c, rankChar, BX - 9f, BY + i * SQ + 13f, 9f, Color.argb(150, 150, 180, 220))
            }
        }
        // Cursor.
        val cx = BX + engine.col(engine.cursor) * SQ
        val cy = BY + engine.row(engine.cursor) * SQ
        val pulse = (180 + 75 * sin(engine.time * 5f)).toInt().coerceIn(80, 255)
        rf.set(cx + 2f, cy + 2f, cx + SQ - 2f, cy + SQ - 2f)
        stroke.strokeWidth = 3f
        stroke.color = Color.argb(pulse, 255, 255, 255)
        c.drawRoundRect(rf, 6f, 6f, stroke)

        // Dwell-to-commit ring: fills over 2s while the cursor rests on a legal
        // target, then locks green — a tap only moves once it's armed.
        if (engine.selected >= 0 && engine.cursor in engine.targets) {
            val ccx = cx + SQ / 2; val ccy = cy + SQ / 2
            rf.set(ccx - SQ * 0.46f, ccy - SQ * 0.46f, ccx + SQ * 0.46f, ccy + SQ * 0.46f)
            stroke.strokeWidth = 4f
            if (engine.moveArmed) {
                val pz = (200 + 55 * sin(engine.time * 6f)).toInt().coerceIn(120, 255)
                stroke.color = Color.argb(pz, 120, 240, 150)
                c.drawArc(rf, -90f, 360f, false, stroke)
            } else {
                stroke.color = Color.argb(90, 255, 220, 140)
                c.drawArc(rf, -90f, 360f, false, stroke)
                stroke.color = Color.argb(230, 255, 210, 120)
                c.drawArc(rf, -90f, 360f * engine.dwellProgress, false, stroke)
            }
        }
    }

    private fun drawOppTrail(c: Canvas) {
        if (engine.oppFrom < 0 || engine.oppTo < 0) return
        val x1 = engine.sqCenterX(engine.oppFrom); val y1 = engine.sqCenterY(engine.oppFrom)
        val x2 = engine.sqCenterX(engine.oppTo); val y2 = engine.sqCenterY(engine.oppTo)
        val dx = x2 - x1; val dy = y2 - y1
        val len = hypot(dx, dy)
        val n = (len / 13f).toInt().coerceAtLeast(2)
        fill.shader = null
        for (i in 1 until n) {
            val t = i.toFloat() / n
            val tw = sin(engine.time * 4f - i * 0.5f) * 0.5f + 0.5f
            fill.color = Color.argb((110 + 120 * tw).toInt().coerceIn(60, 255), 255, 158, 96)
            c.drawCircle(x1 + dx * t, y1 + dy * t, 2.4f + tw * 1.4f, fill)
        }
        val ang = atan2(dy, dx)
        val ah = 9f
        val p = piecePath; p.reset()
        p.moveTo(x2, y2)
        p.lineTo(x2 - ah * cos(ang - 0.42f), y2 - ah * sin(ang - 0.42f))
        p.lineTo(x2 - ah * cos(ang + 0.42f), y2 - ah * sin(ang + 0.42f))
        p.close()
        fill.color = Color.argb(235, 255, 172, 104)
        c.drawPath(p, fill)
    }

    private fun highlightSquare(c: Canvas, sqi: Int, color: Int) {
        val x = BX + engine.col(sqi) * SQ; val y = BY + engine.row(sqi) * SQ
        fill.shader = null
        fill.color = color
        c.drawRect(x, y, x + SQ, y + SQ, fill)
    }

    private fun drawPieces(c: Canvas) {
        for (sqi in 0 until 64) {
            val p = engine.board.sq[sqi]
            if (p == 0) continue
            drawPiece(c, engine.sqCenterX(sqi), engine.sqCenterY(sqi), SQ * 0.74f, abs(p), p > 0)
        }
    }

    // ------------------------------------------------------ piece glyphs

    private fun drawPiece(c: Canvas, cx: Float, cy: Float, size: Float, type: Int, white: Boolean) {
        val s = size / 2f
        fill.shader = null
        fill.color = if (white) whiteFill else blackFill
        stroke.color = if (white) whiteRim else blackRim
        stroke.strokeWidth = 2f
        stroke.strokeJoin = Paint.Join.ROUND
        val p = piecePath
        p.reset()
        when (type) {
            P.PAWN -> {
                c.drawCircle(cx, cy - 0.28f * s, 0.30f * s, fill)
                c.drawCircle(cx, cy - 0.28f * s, 0.30f * s, stroke)
                p.moveTo(cx - 0.18f * s, cy - 0.10f * s)
                p.lineTo(cx + 0.18f * s, cy - 0.10f * s)
                p.lineTo(cx + 0.42f * s, cy + 0.5f * s)
                p.lineTo(cx - 0.42f * s, cy + 0.5f * s)
                p.close()
            }
            P.ROOK -> {
                p.moveTo(cx - 0.5f * s, cy - 0.5f * s)
                p.lineTo(cx - 0.5f * s, cy - 0.2f * s)
                p.lineTo(cx - 0.28f * s, cy - 0.2f * s)
                p.lineTo(cx - 0.28f * s, cy - 0.36f * s)
                p.lineTo(cx - 0.1f * s, cy - 0.36f * s)
                p.lineTo(cx - 0.1f * s, cy - 0.2f * s)
                p.lineTo(cx + 0.1f * s, cy - 0.2f * s)
                p.lineTo(cx + 0.1f * s, cy - 0.36f * s)
                p.lineTo(cx + 0.28f * s, cy - 0.36f * s)
                p.lineTo(cx + 0.28f * s, cy - 0.2f * s)
                p.lineTo(cx + 0.5f * s, cy - 0.2f * s)
                p.lineTo(cx + 0.5f * s, cy - 0.5f * s)
                p.close()
                // body
                rf.set(cx - 0.34f * s, cy - 0.2f * s, cx + 0.34f * s, cy + 0.4f * s)
                c.drawRect(rf, fill); c.drawRect(rf, stroke)
                rf.set(cx - 0.46f * s, cy + 0.4f * s, cx + 0.46f * s, cy + 0.56f * s)
                c.drawRect(rf, fill); c.drawRect(rf, stroke)
            }
            P.KNIGHT -> {
                p.moveTo(cx - 0.32f * s, cy + 0.5f * s)
                p.lineTo(cx - 0.28f * s, cy + 0.1f * s)
                p.lineTo(cx - 0.40f * s, cy - 0.06f * s)
                p.lineTo(cx - 0.30f * s, cy - 0.34f * s)
                p.lineTo(cx - 0.05f * s, cy - 0.52f * s)
                p.lineTo(cx + 0.28f * s, cy - 0.44f * s)
                p.lineTo(cx + 0.42f * s, cy - 0.10f * s)
                p.lineTo(cx + 0.22f * s, cy + 0.16f * s)
                p.lineTo(cx + 0.30f * s, cy + 0.5f * s)
                p.close()
            }
            P.BISHOP -> {
                c.drawCircle(cx, cy - 0.36f * s, 0.12f * s, fill)
                c.drawCircle(cx, cy - 0.36f * s, 0.12f * s, stroke)
                p.moveTo(cx, cy - 0.30f * s)
                p.cubicTo(cx + 0.42f * s, cy - 0.1f * s, cx + 0.28f * s, cy + 0.3f * s, cx + 0.34f * s, cy + 0.42f * s)
                p.lineTo(cx - 0.34f * s, cy + 0.42f * s)
                p.cubicTo(cx - 0.28f * s, cy + 0.3f * s, cx - 0.42f * s, cy - 0.1f * s, cx, cy - 0.30f * s)
                p.close()
                rf.set(cx - 0.44f * s, cy + 0.42f * s, cx + 0.44f * s, cy + 0.56f * s)
                c.drawRect(rf, fill); c.drawRect(rf, stroke)
            }
            P.QUEEN -> {
                p.moveTo(cx - 0.44f * s, cy - 0.4f * s)
                p.lineTo(cx - 0.24f * s, cy + 0.12f * s)
                p.lineTo(cx - 0.12f * s, cy - 0.34f * s)
                p.lineTo(cx, cy + 0.12f * s)
                p.lineTo(cx + 0.12f * s, cy - 0.34f * s)
                p.lineTo(cx + 0.24f * s, cy + 0.12f * s)
                p.lineTo(cx + 0.44f * s, cy - 0.4f * s)
                p.lineTo(cx + 0.34f * s, cy + 0.42f * s)
                p.lineTo(cx - 0.34f * s, cy + 0.42f * s)
                p.close()
                drawCrownDots(c, cx, cy, s, white)
                rf.set(cx - 0.44f * s, cy + 0.42f * s, cx + 0.44f * s, cy + 0.56f * s)
                c.drawRect(rf, fill); c.drawRect(rf, stroke)
            }
            P.KING -> {
                // cross
                stroke.strokeWidth = 3f
                c.drawLine(cx, cy - 0.62f * s, cx, cy - 0.34f * s, stroke)
                c.drawLine(cx - 0.10f * s, cy - 0.5f * s, cx + 0.10f * s, cy - 0.5f * s, stroke)
                stroke.strokeWidth = 2f
                p.moveTo(cx - 0.40f * s, cy - 0.3f * s)
                p.cubicTo(cx - 0.2f * s, cy - 0.36f * s, cx - 0.18f * s, cy - 0.02f * s, cx, cy - 0.1f * s)
                p.cubicTo(cx + 0.18f * s, cy - 0.02f * s, cx + 0.2f * s, cy - 0.36f * s, cx + 0.40f * s, cy - 0.3f * s)
                p.lineTo(cx + 0.34f * s, cy + 0.42f * s)
                p.lineTo(cx - 0.34f * s, cy + 0.42f * s)
                p.close()
                rf.set(cx - 0.44f * s, cy + 0.42f * s, cx + 0.44f * s, cy + 0.56f * s)
                c.drawRect(rf, fill); c.drawRect(rf, stroke)
            }
        }
        if (!p.isEmpty) { c.drawPath(p, fill); c.drawPath(p, stroke) }
        // Subtle top gloss.
        fill.color = Color.argb(60, 255, 255, 255)
        c.drawCircle(cx - 0.12f * s, cy - 0.18f * s, 0.08f * s, fill)
    }

    private fun drawCrownDots(c: Canvas, cx: Float, cy: Float, s: Float, white: Boolean) {
        fill.color = if (white) whiteFill else blackFill
        for (dx in floatArrayOf(-0.44f, -0.12f, 0.12f, 0.44f)) {
            c.drawCircle(cx + dx * s, cy - 0.4f * s, 0.07f * s, fill)
            c.drawCircle(cx + dx * s, cy - 0.4f * s, 0.07f * s, stroke)
        }
    }

    // --------------------------------------------------------------- HUD

    private fun drawHud(c: Canvas) {
        // Top bar: turn / status.
        val turnText: String
        val turnColor: Int
        when {
            engine.state == GameState.THINKING -> {
                val dots = ".".repeat(1 + ((engine.time * 2).toInt() % 3))
                turnText = "${engine.difficulty.label} thinking$dots"
                turnColor = Color.argb(255, 255, 200, 100)
            }
            engine.state == GameState.PROMOTION -> { turnText = "Choose a promotion"; turnColor = Color.argb(255, 200, 220, 255) }
            engine.statusMsg == "Check!" -> { turnText = "CHECK!"; turnColor = Color.argb(255, 255, 90, 90) }
            else -> { turnText = "Your move"; turnColor = Color.argb(255, 150, 235, 170) }
        }
        if (engine.state != GameState.OVER) text(c, turnText, W / 2f, 34f, 16f, turnColor, glow = Color.argb(90, 60, 120, 200))

        // Right panel: opponent + you + clocks + captured.
        val you = if (engine.humanWhite) "White" else "Black"
        val cpu = if (engine.humanWhite) "Black" else "White"
        text(c, "CPU", 570f, 78f, 13f, Color.argb(255, 200, 160, 255), align = Paint.Align.CENTER)
        text(c, engine.difficulty.label.substringAfter("· ").ifEmpty { engine.difficulty.label }, 570f, 96f, 11f, Color.argb(220, 180, 200, 235))
        if (engine.speedOn) drawClock(c, 570f, 122f, if (engine.humanWhite) engine.blackMs else engine.whiteMs, engine.state == GameState.THINKING)
        drawCaptured(c, 508f, 150f, capturedBy = !engine.humanWhite) // pieces CPU took (your losses if you're white)

        text(c, "YOU", 570f, 336f, 13f, Color.argb(255, 150, 235, 170))
        text(c, you, 570f, 354f, 11f, Color.argb(220, 180, 210, 235))
        if (engine.speedOn) drawClock(c, 570f, 380f, if (engine.humanWhite) engine.whiteMs else engine.blackMs, engine.state == GameState.PLAYING)
        drawCaptured(c, 508f, 404f, capturedBy = engine.humanWhite)

        // Left panel: material advantage.
        val adv = materialAdvantage()
        val advColor = when {
            adv > 0 -> Color.argb(255, 150, 235, 170)
            adv < 0 -> Color.argb(255, 255, 140, 140)
            else -> Color.argb(200, 180, 200, 230)
        }
        text(c, if (adv > 0) "+$adv" else if (adv < 0) "$adv" else "even", 72f, 250f, 26f, advColor, glow = Color.argb(80, 80, 150, 255))
        text(c, "material", 72f, 272f, 11f, Color.argb(180, 150, 180, 220))
        text(c, "W ${store.wins}  L ${store.losses}  D ${store.draws}", 72f, 430f, 10f, Color.argb(200, 160, 185, 220))
    }

    private fun drawClock(c: Canvas, cx: Float, y: Float, ms: Long, active: Boolean) {
        val totalSec = (ms / 1000).toInt()
        val mm = totalSec / 60; val ss = totalSec % 60
        val low = ms in 1..10000
        val col = when {
            low -> Color.argb((150 + 100 * sin(engine.time * 8f)).toInt().coerceIn(60, 255), 255, 80, 80)
            active -> Color.argb(255, 255, 240, 180)
            else -> Color.argb(200, 170, 195, 230)
        }
        rf.set(cx - 42f, y - 15f, cx + 42f, y + 9f)
        fill.shader = null
        fill.color = if (active) Color.argb(140, 40, 60, 100) else Color.argb(80, 24, 36, 60)
        c.drawRoundRect(rf, 6f, 6f, fill)
        text(c, "%d:%02d".format(mm, ss), cx, y + 2f, 16f, col)
    }

    private fun drawCaptured(c: Canvas, x0: Float, y0: Float, capturedBy: Boolean) {
        // capturedBy=true -> pieces the white side captured (i.e., black pieces missing).
        val victimWhite = !capturedBy
        val initial = intArrayOf(0, 8, 2, 2, 2, 1, 1)
        val have = engine.board.countMaterial(victimWhite)
        var gx = x0; var gy = y0
        for (type in intArrayOf(P.PAWN, P.KNIGHT, P.BISHOP, P.ROOK, P.QUEEN)) {
            val lost = initial[type] - have[type]
            for (i in 0 until lost) {
                drawPiece(c, gx, gy, 15f, type, victimWhite)
                gx += 15f
                if (gx > 632f) { gx = x0; gy += 17f }
            }
        }
    }

    private fun materialAdvantage(): Int {
        val v = intArrayOf(0, 1, 3, 3, 5, 9, 0)
        var w = 0; var b = 0
        for (i in 0 until 64) {
            val p = engine.board.sq[i]
            if (p > 0) w += v[p] else if (p < 0) b += v[-p]
        }
        val mine = if (engine.humanWhite) w - b else b - w
        return mine
    }

    // -------------------------------------------------------- overlays

    private fun drawInvalid(c: Canvas) {
        val msg = engine.invalidMsg ?: return
        val a = (engine.invalidT / 2.6f).coerceIn(0f, 1f)
        val alpha = (min(1f, a * 3f) * 255).toInt()
        val hint = engine.invalidIsHint
        rf.set(150f, 436f, 490f, 466f)
        fill.shader = null
        // Amber for a "wait for the dwell" hint, red for an illegal move.
        fill.color = if (hint) Color.argb((alpha * 0.85f).toInt(), 54, 44, 16)
        else Color.argb((alpha * 0.85f).toInt(), 60, 20, 24)
        c.drawRoundRect(rf, 12f, 12f, fill)
        stroke.strokeWidth = 2f
        stroke.color = if (hint) Color.argb((alpha * 0.9f).toInt(), 255, 200, 110)
        else Color.argb((alpha * 0.9f).toInt(), 255, 110, 110)
        c.drawRoundRect(rf, 12f, 12f, stroke)
        textP.alpha = 255
        text(c, msg, 320f, 456f, 12.5f, if (hint) Color.argb(alpha, 255, 230, 180) else Color.argb(alpha, 255, 220, 210))
    }

    private fun drawPromo(c: Canvas) {
        dim(c, 150)
        panel(c, 150f, 175f, 490f, 305f)
        text(c, "PROMOTE TO", 320f, 205f, 15f, Color.argb(255, 200, 220, 255))
        val white = engine.humanWhite
        for (i in engine.promoPieces.indices) {
            val x = 205f + i * 76f
            val sel = i == engine.promoIdx
            if (sel) {
                fill.shader = null
                fill.color = Color.argb(210, 40, 70, 110)
                rf.set(x - 30f, 225f, x + 30f, 285f)
                c.drawRoundRect(rf, 10f, 10f, fill)
                stroke.strokeWidth = 2f
                stroke.color = Color.argb(255, 120, 220, 150)
                c.drawRoundRect(rf, 10f, 10f, stroke)
            }
            drawPiece(c, x, 253f, 40f, engine.promoPieces[i], white)
        }
        text(c, "swipe to choose · tap to confirm", 320f, 298f, 11f, Color.argb(200, 180, 205, 235))
    }

    private fun drawOver(c: Canvas) {
        dim(c, 150)
        panel(c, 130f, 170f, 510f, 320f)
        val win = engine.resultMsg.contains("win", true)
        val loss = engine.resultMsg.contains("lose", true) || engine.resultMsg.contains("resign", true)
        val col = if (win) Color.argb(255, 150, 235, 170) else if (loss) Color.argb(255, 255, 120, 120) else Color.argb(255, 210, 220, 245)
        val title = if (win) "VICTORY" else if (loss) "DEFEAT" else "DRAW"
        text(c, title, 320f, 218f, 30f, col, glow = Color.argb(140, 40, 90, 150))
        text(c, engine.resultMsg, 320f, 254f, 13f, Color.argb(255, 210, 224, 245))
        text(c, "TAP FOR MENU", 320f, 296f, 15f, Color.argb((170 + 85 * sin(engine.time * 4f)).toInt().coerceIn(60, 255), 200, 230, 255))
    }

    private fun drawMenu(c: Canvas) {
        // Big knight motif.
        drawPiece(c, 320f, 150f, 96f, P.KNIGHT, true)
        textP.setShadowLayer(16f, 0f, 0f, Color.argb(180, 60, 150, 255))
        text(c, "TAP CHESS", 320f, 240f, 46f, 0xFFEAF3FF.toInt())
        textP.clearShadowLayer()
        text(c, "outsmart the machine", 320f, 266f, 12f, Color.argb(220, 150, 190, 235))

        val d = Difficulty.from(engine.menuDiff)
        text(c, "‹  ${d.label}  ›", 320f, 316f, 22f, Color.WHITE, glow = Color.argb(140, 80, 150, 255))
        text(c, "playing as ${if (store.playerWhite) "White" else "Black"} · speed ${store.speedLabel}", 320f, 342f, 12f, Color.argb(255, 255, 224, 120))
        text(c, "record   W ${store.wins}   L ${store.losses}   D ${store.draws}", 320f, 366f, 12f, Color.argb(220, 156, 255, 176))

        text(c, "swipe ↔ difficulty   •   tap to play", 320f, 410f, 13f, Color.argb((170 + 85 * sin(engine.time * 3f)).toInt().coerceIn(60, 255), 200, 230, 255))
        text(c, "double-tap for settings", 320f, 432f, 11f, Color.argb(160, 150, 175, 210))
    }

    // --------------------------------------------------------- settings

    private fun drawSettings(c: Canvas) {
        dim(c, 188)
        panel(c, 138f, 34f, 502f, 446f)
        text(c, "SETTINGS", 320f, 64f, 20f, Color.WHITE, glow = Color.argb(160, 80, 150, 255))
        val menu = engine.settingsMenu
        val visible = 10
        val start = (menu.selected - visible / 2).coerceIn(0, (menu.items.size - visible).coerceAtLeast(0))
        var y = 96f
        for (i in start until min(start + visible, menu.items.size)) {
            val item = menu.items[i]
            val sel = i == menu.selected
            if (sel) {
                fill.shader = null
                fill.color = Color.argb(210, 36, 64, 106)
                rf.set(150f, y - 15f, 490f, y + 8f)
                c.drawRoundRect(rf, 8f, 8f, fill)
            }
            text(c, item.label, 166f, y, 13f, if (sel) Color.WHITE else Color.argb(255, 159, 180, 208), Paint.Align.LEFT)
            val v = item.value()
            if (v.isNotEmpty()) {
                val shown = if (sel && item.adjust != null) "‹ $v ›" else v
                text(c, shown, 474f, y, 13f, if (sel) Color.argb(255, 255, 224, 128) else Color.argb(255, 120, 144, 176), Paint.Align.RIGHT)
            }
            y += 33f
        }
        if (start > 0) text(c, "▲", 320f, 86f, 10f, Color.argb(180, 150, 180, 220))
        if (start + visible < menu.items.size) text(c, "▼", 320f, 430f, 10f, Color.argb(180, 150, 180, 220))
        text(c, "swipe ↕ select   ↔ adjust   tap OK   double-tap close", 320f, 462f, 10.5f, Color.argb(200, 150, 175, 210))
    }

    // ------------------------------------------------------ fx & helpers

    private fun drawParticles(c: Canvas) {
        for (pt in engine.particles.list) {
            val k = (pt.life / pt.maxLife).coerceIn(0f, 1f)
            val alpha = (k * 255).toInt()
            if (pt.ring) {
                stroke.strokeWidth = 1.5f + 3f * k
                stroke.color = pt.color; stroke.alpha = alpha
                c.drawCircle(pt.x, pt.y, pt.size * (1f + (1f - k) * 2f), stroke)
            } else {
                fill.shader = null
                fill.color = pt.color; fill.alpha = alpha
                c.drawCircle(pt.x, pt.y, pt.size * (0.4f + 0.6f * k), fill)
            }
        }
        stroke.alpha = 255; fill.alpha = 255
    }

    private fun dim(c: Canvas, a: Int) {
        fill.shader = null; fill.color = Color.argb(a, 0, 0, 0)
        c.drawRect(0f, 0f, W, H, fill)
    }

    private fun panel(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        rf.set(l, t, r, b)
        fill.shader = null; fill.color = Color.argb(236, 12, 22, 42)
        c.drawRoundRect(rf, 16f, 16f, fill)
        stroke.strokeWidth = 2f; stroke.color = Color.argb(200, 95, 134, 200)
        c.drawRoundRect(rf, 16f, 16f, stroke)
    }

    private fun text(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        align: Paint.Align = Paint.Align.CENTER, glow: Int = 0,
    ) {
        textP.textSize = size
        textP.textAlign = align
        textP.color = color
        if (glow != 0) textP.setShadowLayer(size * 0.4f, 0f, 0f, glow) else textP.clearShadowLayer()
        c.drawText(s, x, y, textP)
        textP.clearShadowLayer()
    }
}
