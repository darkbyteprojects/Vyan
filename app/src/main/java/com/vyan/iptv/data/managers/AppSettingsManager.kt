package com.vyan.iptv.data.managers

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("XtreamPlayerPrefs", Context.MODE_PRIVATE)

    // PROFILES
    var activeProfileId: String
        get() = prefs.getString("active_profile_id", "default") ?: "default"
        set(value) = prefs.edit().putString("active_profile_id", value).apply()

    var decoderMode: String
        get() = prefs.getString("decoder_mode", "auto") ?: "auto"
        set(value) = prefs.edit().putString("decoder_mode", value).apply()

    // EXISTING PREFS
    var autoIncludePlaylists: Boolean get() = prefs.getBoolean("auto_include_playlists", true); set(value) = prefs.edit().putBoolean("auto_include_playlists", value).apply()
    var isLiveTvAutomatedMode: Boolean get() = prefs.getBoolean("live_tv_automated", false); set(value) = prefs.edit().putBoolean("live_tv_automated", value).apply()
    var appTheme: String get() = prefs.getString("app_theme", "Dark") ?: "Dark"; set(v) = prefs.edit().putString("app_theme", v).apply()
    var enablePip: Boolean get() = prefs.getBoolean("enable_pip", true); set(v) = prefs.edit().putBoolean("enable_pip", v).apply()
    var enablePlayerGestures: Boolean get() = prefs.getBoolean("enable_player_gestures", true); set(v) = prefs.edit().putBoolean("enable_player_gestures", v).apply()
    var decoderType: String get() = prefs.getString("decoder_type", "HW") ?: "HW"; set(v) = prefs.edit().putString("decoder_type", v).apply()
    var bufferSizeSeconds: Int get() = prefs.getInt("buffer_size", 5); set(v) = prefs.edit().putInt("buffer_size", v).apply()
    var httpCleartextOverride: Boolean get() = prefs.getBoolean("http_cleartext", true); set(v) = prefs.edit().putBoolean("http_cleartext", v).apply()
}