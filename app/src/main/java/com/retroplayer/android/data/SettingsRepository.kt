package com.retroplayer.android.data

import android.content.Context

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("retro-player-settings", Context.MODE_PRIVATE)

    var darkTheme: Boolean
        get() = prefs.getBoolean("dark_theme", true)
        set(value) = prefs.edit().putBoolean("dark_theme", value).apply()

    var autoSync: Boolean
        get() = prefs.getBoolean("auto_sync", true)
        set(value) = prefs.edit().putBoolean("auto_sync", value).apply()

    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(value) = prefs.edit().putBoolean("haptics", value).apply()

    var showDiagnostics: Boolean
        get() = prefs.getBoolean("show_diagnostics", false)
        set(value) = prefs.edit().putBoolean("show_diagnostics", value).apply()

    var touchScale: Float
        get() = prefs.getFloat("touch_scale", 1f)
        set(value) = prefs.edit().putFloat("touch_scale", value.coerceIn(0.75f, 1.35f)).apply()

    var volume: Float
        get() = prefs.getFloat("volume", 1f)
        set(value) = prefs.edit().putFloat("volume", value.coerceIn(0f, 1f)).apply()
}
