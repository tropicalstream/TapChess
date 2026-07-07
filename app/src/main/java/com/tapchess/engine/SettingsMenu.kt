package com.tapchess.engine

import com.tapchess.SettingsStore
import com.tapchess.audio.Audio

class SettingsItem(
    val label: String,
    val value: () -> String,
    val adjust: ((Int) -> Unit)? = null,
    val activate: (() -> Unit)? = null,
)

/**
 * Settings overlay (double-tap to enter/exit) using the on-device proven
 * LATCHED stepper (FABLE_X3_STARTER_GUIDE gotcha #25): one swipe gesture = one
 * step, re-armed only after the accumulator settles or the gesture ends.
 */
class SettingsMenu(private val engine: GameEngine, private val store: SettingsStore) {
    var selected = 0
    var confirmingReset = false
    private var accX = 0f
    private var accY = 0f
    private var vLatched = false
    private var hLatched = false

    private val partNames = arrayOf("Low", "Normal", "Ultra")
    private val moveThreshold = 42f
    private val moveRearm = 14f
    private val adjustThreshold = 56f
    private val adjustRearm = 18f

    fun onOpen() {
        selected = 0; accX = 0f; accY = 0f
        vLatched = false; hLatched = false
        confirmingReset = false
    }

    val items: List<SettingsItem> = listOf(
        SettingsItem("Resume", { "" }, activate = { engine.doubleTap() }),
        SettingsItem("Difficulty", { Difficulty.from(store.difficulty).label }, adjust = { d ->
            store.difficulty = (store.difficulty + d).coerceIn(0, 4); engine.menuDiff = store.difficulty
        }),
        SettingsItem("Play As", { if (store.playerWhite) "White" else "Black" }, adjust = {
            store.playerWhite = !store.playerWhite
        }),
        SettingsItem("Speed Mode", { store.speedLabel }, adjust = { d ->
            store.speedIndex = (store.speedIndex + d).coerceIn(0, 4)
        }),
        SettingsItem("Show Legal Moves", { if (store.showLegal) "On" else "Off" }, adjust = {
            store.showLegal = !store.showLegal
        }),
        SettingsItem("Show Coordinates", { if (store.showCoords) "On" else "Off" }, adjust = {
            store.showCoords = !store.showCoords
        }),
        SettingsItem("Sound Volume", { "${store.soundVolume * 10}%" }, adjust = { d ->
            store.soundVolume += d; engine.host.applySettings()
        }),
        SettingsItem("Swipe Sensitivity", { "%.1f".format(store.swipeSens) }, adjust = { d ->
            store.swipeSens += d * 0.1f
        }),
        SettingsItem("Flip Vertical", { if (store.flipVertical) "On" else "Off" }, adjust = {
            store.flipVertical = !store.flipVertical
        }),
        SettingsItem("Flip Horizontal", { if (store.flipHorizontal) "On" else "Off" }, adjust = {
            store.flipHorizontal = !store.flipHorizontal
        }),
        SettingsItem("Safe Tap", { if (store.safeTap) "On" else "Off" }, adjust = {
            store.safeTap = !store.safeTap
        }),
        SettingsItem("Particles", { partNames[store.particlesLevel] }, adjust = { d ->
            store.particlesLevel = (store.particlesLevel + d + 3) % 3; engine.host.applySettings()
        }),
        SettingsItem("Frame Cap", { if (store.frameCap30) "30 fps" else "60 fps" }, adjust = {
            store.frameCap30 = !store.frameCap30; engine.host.applySettings()
        }),
        SettingsItem("Binocular SBS", { if (store.sbs) "On" else "Off" }, adjust = {
            store.sbs = !store.sbs; engine.host.applySettings()
        }),
        SettingsItem("New Game", { "" }, activate = { engine.doubleTap(); engine.newGame() }),
        SettingsItem("Undo Move", { "" }, activate = { engine.doubleTap(); engine.undo() }),
        SettingsItem("Resign", { "" }, activate = { engine.doubleTap(); engine.resign() }),
        SettingsItem("Reset Stats", { if (confirmingReset) "tap again!" else "" }, activate = {
            if (confirmingReset) { store.resetStats(); confirmingReset = false } else confirmingReset = true
        }),
    )

    fun swipe(dx: Float, dy: Float) {
        accY += dy; accX += dx
        if (vLatched && kotlin.math.abs(accY) <= moveRearm) { vLatched = false; accY = 0f }
        if (!vLatched) {
            when {
                accY > moveThreshold -> { move(1); vLatched = true; accY = 0f }
                accY < -moveThreshold -> { move(-1); vLatched = true; accY = 0f }
            }
        }
        if (hLatched && kotlin.math.abs(accX) <= adjustRearm) { hLatched = false; accX = 0f }
        if (!hLatched) {
            when {
                accX > adjustThreshold -> { adjust(1); hLatched = true; accX = 0f }
                accX < -adjustThreshold -> { adjust(-1); hLatched = true; accX = 0f }
            }
        }
    }

    fun endSwipe() { accX = 0f; accY = 0f; vLatched = false; hLatched = false }

    private fun move(d: Int) {
        selected = (selected + d + items.size) % items.size
        confirmingReset = false
        accX = 0f
        engine.host.sound(Audio.TICK)
    }

    private fun adjust(d: Int) {
        items[selected].adjust?.invoke(d) ?: return
        engine.host.sound(Audio.TICK, 1.3f)
    }

    fun activate() {
        val item = items[selected]
        if (item.activate != null) { item.activate.invoke(); engine.host.sound(Audio.SELECT) }
        else { item.adjust?.invoke(1); engine.host.sound(Audio.TICK, 1.3f) }
    }
}
