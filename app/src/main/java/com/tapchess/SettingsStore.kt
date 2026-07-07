package com.tapchess

import android.content.Context
import android.os.Build

/** Persistent settings + stats. RayNeo hardware detected by identity, not model (guide gotcha #24). */
class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("tapchess", Context.MODE_PRIVATE)

    private val deviceText = listOf(
        Build.MODEL, Build.MANUFACTURER, Build.BRAND, Build.DEVICE, Build.PRODUCT
    ).joinToString(" ").lowercase()

    private val isRayNeoX3 =
        "rayneo" in deviceText || "leiniao" in deviceText || "ffalcon" in deviceText ||
            ("x3" in deviceText && ("tcl" in deviceText || "falcon" in deviceText))

    init {
        if (isRayNeoX3 && !p.getBoolean("rayneoSbsV1", false)) {
            p.edit().putBoolean("sbs", true).putBoolean("rayneoSbsV1", true).apply()
        }
    }

    /** 0..4 -> Pawn..Queen (see Difficulty enum). */
    var difficulty: Int
        get() = p.getInt("difficulty", 1)
        set(v) { p.edit().putInt("difficulty", v.coerceIn(0, 4)).apply() }

    var playerWhite: Boolean
        get() = p.getBoolean("playerWhite", true)
        set(v) { p.edit().putBoolean("playerWhite", v).apply() }

    /** Speed mode: 0 = off (untimed), else per-side seconds (600/300/180/60). */
    var speedIndex: Int
        get() = p.getInt("speed", 0)
        set(v) { p.edit().putInt("speed", v.coerceIn(0, 4)).apply() }

    val speedSeconds: Int
        get() = when (speedIndex) { 1 -> 600; 2 -> 300; 3 -> 180; 4 -> 60; else -> 0 }

    val speedLabel: String
        get() = when (speedIndex) { 1 -> "10:00"; 2 -> "5:00"; 3 -> "3:00"; 4 -> "1:00"; else -> "Off" }

    var showLegal: Boolean
        get() = p.getBoolean("showLegal", true)
        set(v) { p.edit().putBoolean("showLegal", v).apply() }

    var showCoords: Boolean
        get() = p.getBoolean("showCoords", true)
        set(v) { p.edit().putBoolean("showCoords", v).apply() }

    var soundVolume: Int
        get() = p.getInt("sndVol", 8)
        set(v) { p.edit().putInt("sndVol", v.coerceIn(0, 10)).apply() }

    var swipeSens: Float
        get() = p.getFloat("swipeSens", 1.0f)
        set(v) { p.edit().putFloat("swipeSens", v.coerceIn(0.4f, 2.5f)).apply() }

    var flipVertical: Boolean
        get() = p.getBoolean("flipV", false)
        set(v) { p.edit().putBoolean("flipV", v).apply() }

    var flipHorizontal: Boolean
        get() = p.getBoolean("flipH", false)
        set(v) { p.edit().putBoolean("flipH", v).apply() }

    var safeTap: Boolean
        get() = p.getBoolean("safeTap", true)
        set(v) { p.edit().putBoolean("safeTap", v).apply() }

    var particlesLevel: Int
        get() = p.getInt("particles", 1)
        set(v) { p.edit().putInt("particles", v.coerceIn(0, 2)).apply() }

    var frameCap30: Boolean
        get() = p.getBoolean("cap30", false)
        set(v) { p.edit().putBoolean("cap30", v).apply() }

    var sbs: Boolean
        get() = p.getBoolean("sbs", isRayNeoX3)
        set(v) { p.edit().putBoolean("sbs", v).apply() }

    var wins: Int
        get() = p.getInt("wins", 0)
        set(v) { p.edit().putInt("wins", v).apply() }

    var losses: Int
        get() = p.getInt("losses", 0)
        set(v) { p.edit().putInt("losses", v).apply() }

    var draws: Int
        get() = p.getInt("draws", 0)
        set(v) { p.edit().putInt("draws", v).apply() }

    fun resetStats() { p.edit().putInt("wins", 0).putInt("losses", 0).putInt("draws", 0).apply() }
}
