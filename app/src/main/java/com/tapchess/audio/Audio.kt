package com.tapchess.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Synthesized SFX (no audio binaries ship). SoundPool for low-latency playback. */
class Audio(private val context: Context) {

    companion object {
        const val MOVE = 0
        const val CAPTURE = 1
        const val CASTLE = 2
        const val CHECK = 3
        const val ILLEGAL = 4
        const val SELECT = 5
        const val TICK = 6
        const val WIN = 7
        const val LOSE = 8
        const val LOWTIME = 9
        private const val COUNT = 10
        private const val RATE = 22050
    }

    private val pool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()

    private val ids = IntArray(COUNT)
    @Volatile private var loaded = false
    @Volatile var volume = 0.8f
    private val rng = Random(9)

    fun loadAsync() {
        Thread {
            runCatching {
                val dir = File(context.cacheDir, "snd").apply { mkdirs() }
                ids[MOVE] = load(dir, "move", synthMove())
                ids[CAPTURE] = load(dir, "cap", synthCapture())
                ids[CASTLE] = load(dir, "cas", arpeggio(intArrayOf(392, 523), 70, 0.6f))
                ids[CHECK] = load(dir, "chk", arpeggio(intArrayOf(660, 880), 80, 0.7f))
                ids[ILLEGAL] = load(dir, "bad", synthIllegal())
                ids[SELECT] = load(dir, "sel", synthSelect())
                ids[TICK] = load(dir, "tick", synthTick())
                ids[WIN] = load(dir, "win", arpeggio(intArrayOf(523, 659, 784, 1046), 110, 0.7f))
                ids[LOSE] = load(dir, "lose", synthLose())
                ids[LOWTIME] = load(dir, "low", synthTick(1400f))
                loaded = true
            }
        }.start()
    }

    fun play(id: Int, pitch: Float = 1f, vol: Float = 1f) {
        if (!loaded || id < 0 || id >= COUNT) return
        val s = ids[id]
        if (s == 0) return
        val v = (volume * vol).coerceIn(0f, 1f)
        if (v <= 0f) return
        pool.play(s, v, v, 1, 0, pitch.coerceIn(0.5f, 2f))
    }

    fun release() { runCatching { pool.release() } }

    // ------------------------------------------------------------ synth

    private fun buf(ms: Int, gen: (Float) -> Float): ShortArray {
        val n = RATE * ms / 1000
        return ShortArray(n) { i -> (gen(i.toFloat() / RATE).coerceIn(-1f, 1f) * 30000f).toInt().toShort() }
    }
    private fun sine(f: Float, t: Float) = sin(2.0 * PI * f * t).toFloat()
    private fun noise() = rng.nextFloat() * 2f - 1f

    private fun synthMove() = buf(90) { t -> (sine(340f, t) * 0.6f + noise() * exp(-t * 60f) * 0.4f) * exp(-t * 20f) }
    private fun synthCapture() = buf(160) { t -> (noise() * 0.6f + sine(180f, t) * 0.5f) * exp(-t * 16f) }
    private fun synthIllegal() = buf(200) { t -> (sine(140f, t) + 0.5f * sine(146f, t)) * exp(-t * 8f) * 0.6f }
    private fun synthSelect() = buf(80) { t -> sine(720f + t * 500f, t) * exp(-t * 16f) * 0.5f }
    private fun synthTick(f: Float = 1100f) = buf(30) { t -> sine(f, t) * exp(-t * 70f) * 0.6f }
    private fun synthLose() = buf(600) { t ->
        val f = if (t < 0.3f) 330f - t * 120f else 220f - (t - 0.3f) * 100f
        sine(f, t) * exp(-t * 3f) * 0.6f
    }

    private fun arpeggio(freqs: IntArray, noteMs: Int, amp: Float): ShortArray {
        val total = noteMs * freqs.size + 240
        return buf(total) { t ->
            var v = 0f
            for ((i, f) in freqs.withIndex()) {
                val start = i * noteMs / 1000f
                if (t >= start) {
                    val lt = t - start
                    v += (sine(f.toFloat(), lt) + 0.3f * sine(f * 2f, lt)) * exp(-lt * 5.5f) * amp * 0.45f
                }
            }
            v
        }
    }

    // ------------------------------------------------------------- wav

    private fun DataOutputStream.wInt(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF) }
    private fun DataOutputStream.wShort(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF) }

    private fun load(dir: File, name: String, pcm: ShortArray): Int {
        val f = File(dir, "$name.wav")
        val dataLen = pcm.size * 2
        DataOutputStream(BufferedOutputStream(FileOutputStream(f))).use { o ->
            o.writeBytes("RIFF"); o.wInt(36 + dataLen); o.writeBytes("WAVE")
            o.writeBytes("fmt "); o.wInt(16); o.wShort(1); o.wShort(1)
            o.wInt(RATE); o.wInt(RATE * 2); o.wShort(2); o.wShort(16)
            o.writeBytes("data"); o.wInt(dataLen)
            for (s in pcm) o.wShort(s.toInt())
        }
        return pool.load(f.absolutePath, 1)
    }
}
