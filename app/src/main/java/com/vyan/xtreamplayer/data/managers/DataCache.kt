package com.vyan.xtreamplayer.data.managers

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vyan.xtreamplayer.models.LiveCategory
import com.vyan.xtreamplayer.models.LiveChannel
import com.vyan.xtreamplayer.models.SeriesCategory
import com.vyan.xtreamplayer.models.SeriesItem
import com.vyan.xtreamplayer.models.VodCategory
import com.vyan.xtreamplayer.models.VodMovie
import com.vyan.xtreamplayer.ui.screens.AggregatedChannel
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object DataCache {
    val liveCategories = ConcurrentHashMap<String, List<LiveCategory>>()
    val vodCategories = ConcurrentHashMap<String, List<VodCategory>>()
    val seriesCategories = ConcurrentHashMap<String, List<SeriesCategory>>()

    val liveChannels = ConcurrentHashMap<String, List<LiveChannel>>()
    val vodMovies = ConcurrentHashMap<String, List<VodMovie>>()
    val seriesItems = ConcurrentHashMap<String, List<SeriesItem>>()

    val hitEpgCache = ConcurrentHashMap<String, String>()
    val aggregatedChannelsCache = ConcurrentHashMap<String, List<AggregatedChannel>>()
    val scannedPortalsTracker = ConcurrentHashMap<String, Set<String>>()

    private var isCacheLoaded = false
    private const val PREFS_NAME = "AggregatedCachePrefs"

    fun loadPersistentCacheIfNeeded(context: Context) {
        try {
            // FIXED: Loading from the unbreakable context.filesDir
            val file = File(context.filesDir, "permanent_aggregated_channels.json")
            if (file.exists() && aggregatedChannelsCache.isEmpty()) {
                val json = file.readText()
                val type = object : TypeToken<Map<String, List<AggregatedChannel>>>() {}.type
                val loaded: Map<String, List<AggregatedChannel>>? = Gson().fromJson(json, type)
                if (loaded != null) {
                    aggregatedChannelsCache.putAll(loaded)
                }
            }

            val portalsFile = File(context.filesDir, "permanent_scanned_portals.json")
            if (portalsFile.exists() && scannedPortalsTracker.isEmpty()) {
                val json = portalsFile.readText()
                val type = object : TypeToken<Map<String, Set<String>>>() {}.type
                val loaded: Map<String, Set<String>>? = Gson().fromJson(json, type)
                if (loaded != null) {
                    scannedPortalsTracker.putAll(loaded)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun savePersistentCache(context: Context) {
        try {
            // FIXED: Using context.filesDir guarantees the OS will NEVER delete this file.
            val file = File(context.filesDir, "permanent_aggregated_channels.json")
            val json = Gson().toJson(aggregatedChannelsCache)
            file.writeText(json)

            // Also save the scanned portals tracker
            val portalsFile = File(context.filesDir, "permanent_scanned_portals.json")
            val portalsJson = Gson().toJson(scannedPortalsTracker)
            portalsFile.writeText(portalsJson)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun removePortalData(context: Context?, url: String, username: String) {
        liveChannels.keys.removeIf { it.startsWith(url) || it.contains(username) }
        liveCategories.keys.removeIf { it.startsWith(url) || it.contains(username) }

        aggregatedChannelsCache.forEach { (categoryId, channelList) ->
            aggregatedChannelsCache[categoryId] = channelList.filterNot {
                it.portalUrl == url && it.username == username
            }
        }

        // Save the cleaned memory to storage instantly
        context?.let { savePersistentCache(it) }
    }

    fun clearAll(context: Context?) {
        liveCategories.clear()
        vodCategories.clear()
        seriesCategories.clear()
        liveChannels.clear()
        vodMovies.clear()
        seriesItems.clear()
        hitEpgCache.clear()
        aggregatedChannelsCache.clear()
        scannedPortalsTracker.clear()
        context?.let { savePersistentCache(it) }
    }
}