package com.tapchess.engine

import kotlin.math.abs
import kotlin.math.max

/** Piece types (unsigned). Sign carries color: white > 0, black < 0. */
object P {
    const val EMPTY = 0
    const val PAWN = 1
    const val KNIGHT = 2
    const val BISHOP = 3
    const val ROOK = 4
    const val QUEEN = 5
    const val KING = 6
    const val WHITE = 1
    const val BLACK = -1
}

const val F_NORMAL = 0
const val F_DOUBLE = 1     // two-square pawn push (sets en-passant target)
const val F_ENPASSANT = 2
const val F_CASTLE = 3
const val F_PROMO = 4

data class Move(val from: Int, val to: Int, val promo: Int = 0, val flag: Int = F_NORMAL)

fun file(sq: Int) = sq and 7
fun rank(sq: Int) = sq shr 3
fun sqOf(f: Int, r: Int) = r * 8 + f
fun onBoard(f: Int, r: Int) = f in 0..7 && r in 0..7

/** Result of asking "can this piece go here?" */
sealed class MoveOutcome {
    class Legal(val moves: List<Move>) : MoveOutcome() // >1 only for promotions
    class Illegal(val reason: String) : MoveOutcome()
}

private val KNIGHT_D = arrayOf(
    intArrayOf(1, 2), intArrayOf(2, 1), intArrayOf(2, -1), intArrayOf(1, -2),
    intArrayOf(-1, -2), intArrayOf(-2, -1), intArrayOf(-2, 1), intArrayOf(-1, 2)
)
private val KING_D = arrayOf(
    intArrayOf(1, 0), intArrayOf(1, 1), intArrayOf(0, 1), intArrayOf(-1, 1),
    intArrayOf(-1, 0), intArrayOf(-1, -1), intArrayOf(0, -1), intArrayOf(1, -1)
)
private val DIAG = arrayOf(intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1))
private val ORTHO = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))

class Board {
    val sq = IntArray(64)
    var side = P.WHITE
    // castling rights: 0=WK 1=WQ 2=BK 3=BQ
    val castling = booleanArrayOf(true, true, true, true)
    var epTarget = -1
    var halfmove = 0
    var fullmove = 1

    fun clone(): Board {
        val b = Board()
        System.arraycopy(sq, 0, b.sq, 0, 64)
        b.side = side
        System.arraycopy(castling, 0, b.castling, 0, 4)
        b.epTarget = epTarget
        b.halfmove = halfmove
        b.fullmove = fullmove
        return b
    }

    fun whiteToMove() = side == P.WHITE

    fun kingSquare(white: Boolean): Int {
        val k = if (white) P.KING else -P.KING
        for (i in 0 until 64) if (sq[i] == k) return i
        return -1
    }

    fun inCheck(white: Boolean): Boolean {
        val ks = kingSquare(white)
        return ks >= 0 && isAttacked(ks, byWhite = !white)
    }

    /** Is `target` attacked by any piece of the given color? */
    fun isAttacked(target: Int, byWhite: Boolean): Boolean {
        val tf = file(target); val tr = rank(target)
        // pawns: a byWhite pawn attacking `target` sits one rank *below* it.
        val pr = if (byWhite) tr - 1 else tr + 1
        val pawn = if (byWhite) P.PAWN else -P.PAWN
        if (pr in 0..7) {
            if (tf - 1 >= 0 && sq[sqOf(tf - 1, pr)] == pawn) return true
            if (tf + 1 <= 7 && sq[sqOf(tf + 1, pr)] == pawn) return true
        }
        val knight = if (byWhite) P.KNIGHT else -P.KNIGHT
        for (d in KNIGHT_D) {
            val f = tf + d[0]; val r = tr + d[1]
            if (onBoard(f, r) && sq[sqOf(f, r)] == knight) return true
        }
        val king = if (byWhite) P.KING else -P.KING
        for (d in KING_D) {
            val f = tf + d[0]; val r = tr + d[1]
            if (onBoard(f, r) && sq[sqOf(f, r)] == king) return true
        }
        val bishop = if (byWhite) P.BISHOP else -P.BISHOP
        val queen = if (byWhite) P.QUEEN else -P.QUEEN
        for (d in DIAG) {
            var f = tf + d[0]; var r = tr + d[1]
            while (onBoard(f, r)) {
                val p = sq[sqOf(f, r)]
                if (p != 0) { if (p == bishop || p == queen) return true; break }
                f += d[0]; r += d[1]
            }
        }
        val rook = if (byWhite) P.ROOK else -P.ROOK
        for (d in ORTHO) {
            var f = tf + d[0]; var r = tr + d[1]
            while (onBoard(f, r)) {
                val p = sq[sqOf(f, r)]
                if (p != 0) { if (p == rook || p == queen) return true; break }
                f += d[0]; r += d[1]
            }
        }
        return false
    }

    // ------------------------------------------------------- generation

    fun generatePseudo(): MutableList<Move> {
        val moves = ArrayList<Move>(48)
        val white = whiteToMove()
        for (from in 0 until 64) {
            val p = sq[from]
            if (p == 0 || (p > 0) != white) continue
            when (abs(p)) {
                P.PAWN -> pawnMoves(from, white, moves)
                P.KNIGHT -> stepMoves(from, KNIGHT_D, white, moves)
                P.KING -> { stepMoves(from, KING_D, white, moves); castleMoves(from, white, moves) }
                P.BISHOP -> slideMoves(from, DIAG, white, moves)
                P.ROOK -> slideMoves(from, ORTHO, white, moves)
                P.QUEEN -> { slideMoves(from, DIAG, white, moves); slideMoves(from, ORTHO, white, moves) }
            }
        }
        return moves
    }

    private fun own(p: Int, white: Boolean) = p != 0 && (p > 0) == white
    private fun enemy(p: Int, white: Boolean) = p != 0 && (p > 0) != white

    private fun stepMoves(from: Int, deltas: Array<IntArray>, white: Boolean, out: MutableList<Move>) {
        val f0 = file(from); val r0 = rank(from)
        for (d in deltas) {
            val f = f0 + d[0]; val r = r0 + d[1]
            if (!onBoard(f, r)) continue
            val to = sqOf(f, r)
            if (!own(sq[to], white)) out.add(Move(from, to))
        }
    }

    private fun slideMoves(from: Int, dirs: Array<IntArray>, white: Boolean, out: MutableList<Move>) {
        val f0 = file(from); val r0 = rank(from)
        for (d in dirs) {
            var f = f0 + d[0]; var r = r0 + d[1]
            while (onBoard(f, r)) {
                val to = sqOf(f, r)
                val p = sq[to]
                if (p == 0) out.add(Move(from, to))
                else { if (enemy(p, white)) out.add(Move(from, to)); break }
                f += d[0]; r += d[1]
            }
        }
    }

    private fun pawnMoves(from: Int, white: Boolean, out: MutableList<Move>) {
        val dir = if (white) 1 else -1
        val startRank = if (white) 1 else 6
        val promoRank = if (white) 7 else 0
        val f0 = file(from); val r0 = rank(from)
        val r1 = r0 + dir
        if (r1 in 0..7 && sq[sqOf(f0, r1)] == 0) {
            addPawn(from, sqOf(f0, r1), r1 == promoRank, F_NORMAL, out)
            if (r0 == startRank && sq[sqOf(f0, r0 + 2 * dir)] == 0) {
                out.add(Move(from, sqOf(f0, r0 + 2 * dir), 0, F_DOUBLE))
            }
        }
        for (df in intArrayOf(-1, 1)) {
            val f = f0 + df
            if (f !in 0..7 || r1 !in 0..7) continue
            val to = sqOf(f, r1)
            if (enemy(sq[to], white)) addPawn(from, to, r1 == promoRank, F_NORMAL, out)
            else if (to == epTarget) out.add(Move(from, to, 0, F_ENPASSANT))
        }
    }

    private fun addPawn(from: Int, to: Int, promo: Boolean, flag: Int, out: MutableList<Move>) {
        if (promo) {
            out.add(Move(from, to, P.QUEEN, F_PROMO))
            out.add(Move(from, to, P.ROOK, F_PROMO))
            out.add(Move(from, to, P.BISHOP, F_PROMO))
            out.add(Move(from, to, P.KNIGHT, F_PROMO))
        } else out.add(Move(from, to, 0, flag))
    }

    private fun castleMoves(from: Int, white: Boolean, out: MutableList<Move>) {
        if (white && from == 4) {
            if (castling[0] && sq[5] == 0 && sq[6] == 0 && sq[7] == P.ROOK) out.add(Move(4, 6, 0, F_CASTLE))
            if (castling[1] && sq[3] == 0 && sq[2] == 0 && sq[1] == 0 && sq[0] == P.ROOK) out.add(Move(4, 2, 0, F_CASTLE))
        } else if (!white && from == 60) {
            if (castling[2] && sq[61] == 0 && sq[62] == 0 && sq[63] == -P.ROOK) out.add(Move(60, 62, 0, F_CASTLE))
            if (castling[3] && sq[59] == 0 && sq[58] == 0 && sq[57] == 0 && sq[56] == -P.ROOK) out.add(Move(60, 58, 0, F_CASTLE))
        }
    }

    fun generateLegal(): List<Move> {
        val white = whiteToMove()
        val pseudo = generatePseudo()
        val legal = ArrayList<Move>(pseudo.size)
        for (m in pseudo) {
            if (m.flag == F_CASTLE) {
                if (inCheck(white)) continue
                val mid = (m.from + m.to) / 2
                if (isAttacked(m.from, !white) || isAttacked(mid, !white) || isAttacked(m.to, !white)) continue
            }
            val b2 = applied(m)
            if (!b2.isAttacked(b2.kingSquare(white), byWhite = !white)) legal.add(m)
        }
        return legal
    }

    /** Return a new board with `m` played. Assumes m is at least pseudo-legal. */
    fun applied(m: Move): Board {
        val b = clone()
        val moving = b.sq[m.from]
        val white = moving > 0
        when (m.flag) {
            F_ENPASSANT -> {
                b.sq[m.to] = moving; b.sq[m.from] = 0
                b.sq[sqOf(file(m.to), rank(m.from))] = 0 // captured pawn sits beside
            }
            F_CASTLE -> {
                b.sq[m.to] = moving; b.sq[m.from] = 0
                when (m.to) {
                    6 -> { b.sq[5] = b.sq[7]; b.sq[7] = 0 }
                    2 -> { b.sq[3] = b.sq[0]; b.sq[0] = 0 }
                    62 -> { b.sq[61] = b.sq[63]; b.sq[63] = 0 }
                    58 -> { b.sq[59] = b.sq[56]; b.sq[56] = 0 }
                }
            }
            F_PROMO -> {
                b.sq[m.to] = if (white) m.promo else -m.promo
                b.sq[m.from] = 0
            }
            else -> { b.sq[m.to] = moving; b.sq[m.from] = 0 }
        }
        b.epTarget = if (m.flag == F_DOUBLE) (m.from + m.to) / 2 else -1
        // castling-rights maintenance
        if (moving == P.KING) { b.castling[0] = false; b.castling[1] = false }
        if (moving == -P.KING) { b.castling[2] = false; b.castling[3] = false }
        if (m.from == 0 || m.to == 0) b.castling[1] = false
        if (m.from == 7 || m.to == 7) b.castling[0] = false
        if (m.from == 56 || m.to == 56) b.castling[3] = false
        if (m.from == 63 || m.to == 63) b.castling[2] = false
        b.halfmove = if (abs(moving) == P.PAWN || sq[m.to] != 0) 0 else b.halfmove + 1
        if (!white) b.fullmove++
        b.side = -b.side
        return b
    }

    // ------------------------------------------------ explain a user move

    fun classify(from: Int, to: Int): MoveOutcome {
        val piece = sq[from]
        if (piece == 0) return MoveOutcome.Illegal("There's no piece on that square.")
        if ((piece > 0) != whiteToMove()) return MoveOutcome.Illegal("That's not your piece to move.")
        val legal = generateLegal().filter { it.from == from && it.to == to }
        if (legal.isNotEmpty()) return MoveOutcome.Legal(legal)
        val pseudo = generatePseudo().filter { it.from == from && it.to == to }
        if (pseudo.isNotEmpty()) {
            return if (inCheck(whiteToMove()))
                MoveOutcome.Illegal("You're in check — that move doesn't save your king.")
            else MoveOutcome.Illegal("That would leave your own king in check.")
        }
        return MoveOutcome.Illegal(patternReason(from, to, piece))
    }

    private fun patternReason(from: Int, to: Int, piece: Int): String {
        if (own(sq[to], piece > 0)) return "Your own piece is already on that square."
        val df = file(to) - file(from)
        val dr = rank(to) - rank(from)
        return when (abs(piece)) {
            P.PAWN -> {
                val dir = if (piece > 0) 1 else -1
                when {
                    df == 0 && dr == dir && sq[to] != 0 -> "A pawn can't capture straight ahead."
                    df == 0 && dr == 2 * dir -> "A pawn only advances two squares from its start, onto an empty file."
                    df == 0 && dr == dir -> "The square directly ahead is blocked."
                    abs(df) == 1 && dr == dir -> "A pawn captures diagonally only onto an enemy piece."
                    else -> "Pawns move forward one square, and capture one square diagonally."
                }
            }
            P.KNIGHT -> "A knight moves in an L — two squares one way, then one across."
            P.BISHOP -> if (abs(df) != abs(dr)) "A bishop moves only diagonally." else "The bishop's path is blocked."
            P.ROOK -> if (df != 0 && dr != 0) "A rook moves in straight lines, not diagonally." else "The rook's path is blocked."
            P.QUEEN -> if (df != 0 && dr != 0 && abs(df) != abs(dr))
                "A queen moves straight or diagonally — that square is neither." else "The queen's path is blocked."
            P.KING -> if (max(abs(df), abs(dr)) > 1) "A king moves only one square (or castles)." else "The king can't move there."
            else -> "That piece can't move like that."
        }
    }

    /** Material still on the board for `white`, keyed by piece type. */
    fun countMaterial(white: Boolean): IntArray {
        val c = IntArray(7)
        for (i in 0 until 64) {
            val p = sq[i]
            if (p != 0 && (p > 0) == white) c[abs(p)]++
        }
        return c
    }

    fun insufficientMaterial(): Boolean {
        var minors = 0
        for (i in 0 until 64) {
            when (abs(sq[i])) {
                P.PAWN, P.ROOK, P.QUEEN -> return false
                P.BISHOP, P.KNIGHT -> minors++
            }
        }
        return minors <= 1
    }

    companion object {
        fun initial(): Board {
            val b = Board()
            val back = intArrayOf(P.ROOK, P.KNIGHT, P.BISHOP, P.QUEEN, P.KING, P.BISHOP, P.KNIGHT, P.ROOK)
            for (f in 0 until 8) {
                b.sq[sqOf(f, 0)] = back[f]
                b.sq[sqOf(f, 1)] = P.PAWN
                b.sq[sqOf(f, 6)] = -P.PAWN
                b.sq[sqOf(f, 7)] = -back[f]
            }
            return b
        }
    }
}
