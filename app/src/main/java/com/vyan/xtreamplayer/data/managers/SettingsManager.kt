package com.vyan.xtreamplayer.data.managers

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.vyan.xtreamplayer.core.HardcodedChannels

data class UserCustomCategory(
    val id: String? = null,
    val name: String? = null,
    val keywords: String? = null,
    val exclude: String? = null,
    val filters: String? = null,
    val colorThemeIndex: Int? = 0,
    val isBuiltIn: Boolean = false
)

data class AggregatorProfile(
    val id: String = "default",
    val name: String = "All Sources",
    val useFreePortals: Boolean = true,
    val playlistIds: List<String> = emptyList(),
    val categoryIds: List<String> = emptyList() // Added for Category Binding
)

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("XtreamPlayerPrefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    init { seedDefaultCategories() }

    fun getCustomCategories(): List<UserCustomCategory> {
        return try {
            val json = prefs.getString("custom_categories_v2", null) ?: return emptyList()
            val type = object : com.google.gson.reflect.TypeToken<List<UserCustomCategory>>() {}.type
            val rawList: List<UserCustomCategory>? = gson.fromJson(json, type)
            rawList?.map { cat ->
                UserCustomCategory(
                    id = cat.id ?: System.currentTimeMillis().toString(),
                    name = cat.name ?: "Unnamed Category",
                    keywords = cat.keywords ?: "",
                    exclude = cat.exclude ?: "",
                    filters = cat.filters ?: "Global",
                    colorThemeIndex = cat.colorThemeIndex ?: 0,
                    isBuiltIn = cat.isBuiltIn
                )
            } ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    private fun saveCustomCategories(list: List<UserCustomCategory>) {
        try { prefs.edit().putString("custom_categories_v2", gson.toJson(list)).apply() } catch (e: Exception) {}
    }

    fun addCustomCategory(cat: UserCustomCategory) {
        val current = getCustomCategories().toMutableList()
        current.removeAll { it.id == cat.id }
        current.add(cat)
        saveCustomCategories(current)
    }

    fun updateCustomCategory(cat: UserCustomCategory) {
        val current = getCustomCategories().toMutableList()
        val index = current.indexOfFirst { it.id == cat.id }
        if (index != -1) current[index] = cat else current.add(cat)
        saveCustomCategories(current)
    }

    fun updateCustomCategoriesOrder(list: List<UserCustomCategory>) {
        saveCustomCategories(list)
    }

    fun deleteCustomCategory(id: String) {
        val current = getCustomCategories().toMutableList()
        current.removeAll { it.id == id }
        saveCustomCategories(current)
    }

    fun resetToDefaultCategories() {
        prefs.edit().remove("custom_categories_v2").apply()
        seedDefaultCategories()
    }

    private fun seedDefaultCategories() {
        if (prefs.contains("custom_categories_v2")) return
        val defaults = HardcodedChannels.all.mapIndexed { index, hc ->
            UserCustomCategory(
                id = hc.id, name = hc.name, keywords = hc.keywords.joinToString(", "),
                exclude = hc.exclude.joinToString(", "), filters = hc.filters.joinToString(", "),
                colorThemeIndex = index % 12, isBuiltIn = true
            )
        }
        saveCustomCategories(defaults)
    }

    // PROFILES
    var activeProfileId: String
        get() = prefs.getString("active_profile_id", "default") ?: "default"
        set(value) = prefs.edit().putString("active_profile_id", value).apply()

    fun getProfiles(): List<AggregatorProfile> {
        return try {
            val json = prefs.getString("aggregator_profiles", null) ?: return listOf(AggregatorProfile("default", "Default Profile (All Sources)", true, emptyList(), emptyList()))
            val type = object : com.google.gson.reflect.TypeToken<List<AggregatorProfile>>() {}.type
            val profiles: List<AggregatorProfile>? = gson.fromJson(json, type)
            if (profiles.isNullOrEmpty()) listOf(AggregatorProfile("default", "Default Profile (All Sources)", true, emptyList(), emptyList())) else profiles
        } catch (e: Exception) { listOf(AggregatorProfile("default", "Default Profile (All Sources)", true, emptyList(), emptyList())) }
    }

    fun saveProfiles(list: List<AggregatorProfile>) {
        try { prefs.edit().putString("aggregator_profiles", gson.toJson(list)).apply() } catch (e: Exception) {}
    }

    fun deleteProfile(id: String) {
        val current = getProfiles().toMutableList()
        current.removeAll { it.id == id }
        if (current.isEmpty()) current.add(AggregatorProfile("default", "Default Profile (All Sources)", true, emptyList(), emptyList()))
        saveProfiles(current)
        if (activeProfileId == id) activeProfileId = current.first().id
    }

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