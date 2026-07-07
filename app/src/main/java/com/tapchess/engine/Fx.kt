package com.tapchess.engine

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class Particle {
    var x = 0f; var y = 0f
    var vx = 0f; var vy = 0f
    var life = 0f; var maxLife = 1f
    var size = 3f; var color = 0
    var ring = false; var drag = 0.9f; var grav = 0f
}

/** Rising label (score, "CORRECT!", token name on reveal). */
class FloatText(
    var text: String, var x: Float, var y: Float,
    var life: Float, var maxLife: Float, var color: Int, var big: Boolean,
)

class ParticleSystem {
    val list = ArrayList<Particle>(256)
    private val pool = ArrayDeque<Particle>(256)
    private val rng = Random(11)
    var budget = 1f

    private fun obtain() = pool.removeFirstOrNull() ?: Particle()

    private fun add(
        x: Float, y: Float, vx: Float, vy: Float, life: Float,
        size: Float, color: Int, ring: Boolean = false, drag: Float = 0.9f, grav: Float = 0f,
    ) {
        if (list.size > 600) return
        val p = obtain()
        p.x = x; p.y = y; p.vx = vx; p.vy = vy
        p.life = life; p.maxLife = life
        p.size = size; p.color = color; p.ring = ring; p.drag = drag; p.grav = grav
        list.add(p)
    }

    private fun n(base: Int) = (base * budget).toInt().coerceAtLeast(1)

    fun burst(x: Float, y: Float, color: Int, power: Float = 1f) {
        add(x, y, 0f, 0f, 0.4f, 30f * power, color, ring = true)
        repeat(n((10 * power).toInt())) {
            val a = rng.nextFloat() * 6.2832f
            val sp = (60f + rng.nextFloat() * 200f) * power
            add(x, y, cos(a) * sp, sin(a) * sp, 0.4f + rng.nextFloat() * 0.4f,
                1.5f + rng.nextFloat() * 2.5f, color, drag = 0.9f, grav = 120f)
        }
    }

    fun ring(x: Float, y: Float, color: Int, size: Float) =
        add(x, y, 0f, 0f, 0.45f, size, color, ring = true)

    fun comet(x: Float, y: Float, color: Int) {
        add(x, y, (rng.nextFloat() - 0.5f) * 30f, (rng.nextFloat() - 0.5f) * 30f,
            0.35f, 2f + rng.nextFloat() * 2f, color, drag = 0.9f)
    }

    fun confetti(w: Float, color: Int) {
        repeat(n(3)) {
            add(rng.nextFloat() * w, -10f, (rng.nextFloat() - 0.5f) * 40f,
                40f + rng.nextFloat() * 80f, 1.6f + rng.nextFloat() * 1.2f,
                2f + rng.nextFloat() * 3f, color, drag = 0.98f, grav = 60f)
        }
    }

    fun update(dt: Float) {
        var i = list.size - 1
        while (i >= 0) {
            val p = list[i]
            p.life -= dt
            if (p.life <= 0f) {
                list.removeAt(i); pool.addLast(p)
            } else {
                p.vy += p.grav * dt
                val d = Math.pow(p.drag.toDouble(), (dt * 60f).toDouble()).toFloat()
                p.vx *= d; p.vy *= d
                p.x += p.vx * dt; p.y += p.vy * dt
            }
            i--
        }
    }

    fun clear() {
        for (p in list) pool.addLast(p)
        list.clear()
    }
}
