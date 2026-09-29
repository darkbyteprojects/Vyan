package com.vyan.iptv.data.managers

import android.content.Context
import com.vyan.iptv.models.LiveCategory
import com.vyan.iptv.models.XtreamLiveChannelModel
import com.vyan.iptv.models.SeriesCategory
import com.vyan.iptv.models.SeriesItem
import com.vyan.iptv.models.VodCategory
import com.vyan.iptv.models.VodMovie
import java.util.concurrent.ConcurrentHashMap

object DataCache {
    val liveCategories = ConcurrentHashMap<String, List<LiveCategory>>()
    val vodCategories = ConcurrentHashMap<String, List<VodCategory>>()
    val seriesCategories = ConcurrentHashMap<String, List<SeriesCategory>>()

    val liveChannels = ConcurrentHashMap<String, List<XtreamLiveChannelModel>>()
    val vodMovies = ConcurrentHashMap<String, List<VodMovie>>()
    val seriesItems = ConcurrentHashMap<String, List<SeriesItem>>()

    val hitEpgCache = ConcurrentHashMap<String, String>()

    fun removePortalData(context: Context?, url: String, username: String) {
        liveChannels.keys.removeIf { it.startsWith(url) || it.contains(username) }
        liveCategories.keys.removeIf { it.startsWith(url) || it.contains(username) }
    }

    fun clearAll(context: Context?) {
        liveCategories.clear()
        vodCategories.clear()
        seriesCategories.clear()
        liveChannels.clear()
        vodMovies.clear()
        seriesItems.clear()
        hitEpgCache.clear()
    }
}