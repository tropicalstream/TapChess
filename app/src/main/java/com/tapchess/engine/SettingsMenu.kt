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
 * Settings overlay (double-tap to enter/exit). Navigation is DISCRETE: one
 * temple-pad swipe gesture = exactly one step, classified on finger-up by the
 * Activity and delivered here via [onDir]. (The earlier continuous-accumulator
 * latch felt laggy and finicky on the X3 pad — one-gesture-one-step is
 * deterministic.) Up/down move the selection; left/right adjust the value.
 *
 * Binocular SBS lives at the very bottom by convention across the app suite —
 * it's a display default you set once, not something you touch mid-session.
 */
class SettingsMenu(private val engine: GameEngine, private val store: SettingsStore) {
    var selected = 0
    var confirmingReset = false
    var confirmingResetSettings = false

    private val partNames = arrayOf("Low", "Normal", "Ultra")

    fun onOpen() {
        selected = 0
        confirmingReset = false
        confirmingResetSettings = false
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
        SettingsItem("New Game", { "" }, activate = { engine.doubleTap(); engine.newGame() }),
        SettingsItem("Undo Move", { "" }, activate = { engine.doubleTap(); engine.undo() }),
        SettingsItem("Resign", { "" }, activate = { engine.doubleTap(); engine.resign() }),
        SettingsItem("Reset Stats", { if (confirmingReset) "tap again!" else "" }, activate = {
            if (confirmingReset) { store.resetStats(); confirmingReset = false } else confirmingReset = true
        }),
        // Kept at the bottom by suite convention.
        SettingsItem("Reset Settings", { if (confirmingResetSettings) "tap again!" else "" }, activate = {
            if (confirmingResetSettings) {
                store.resetSettings()
                engine.host.applySettings()
                engine.menuDiff = store.difficulty
                confirmingResetSettings = false
            } else confirmingResetSettings = true
        }),
    )

    /** One discrete step from a single swipe gesture. dir: 0 up,1 down,2 left,3 right. */
    fun onDir(dir: Int) {
        when (dir) {
            0 -> move(-1)
            1 -> move(1)
            2 -> adjust(-1)
            3 -> adjust(1)
        }
    }

    private fun move(d: Int) {
        selected = (selected + d + items.size) % items.size
        confirmingReset = false
        confirmingResetSettings = false
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
