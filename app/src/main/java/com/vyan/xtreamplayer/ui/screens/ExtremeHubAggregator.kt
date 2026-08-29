package com.vyan.xtreamplayer.ui.screens

import com.vyan.xtreamplayer.core.ExtremeChannel
import com.vyan.xtreamplayer.core.ExtremeSourceConfig
import com.vyan.xtreamplayer.core.ExtremeSourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

object ExtremeHubAggregator {
    val cachedChannels = ConcurrentHashMap<String, List<ExtremeChannel>>()

    suspend fun syncSources(configs: List<ExtremeSourceConfig>) = coroutineScope {
        val deferreds = configs.map { config ->
            async(Dispatchers.IO) {
                try {
                    val channels = ExtremeSourceRegistry.fetchChannels(config)
                    if (channels.isNotEmpty()) {
                        cachedChannels[config.id] = channels
                    }
                } catch (e: Exception) {
                    // Fail silently in background loop
                }
            }
        }
        deferreds.awaitAll()
    }

    // This function was missing! It filters the channels by the selected brand.
    fun getAggregatedBrand(brand: ExtremeBrandCategory): List<ExtremeChannel> {
        val allChannels = cachedChannels.values.flatten()
        return allChannels.filter { ch ->
            val lower = ch.name.lowercase()
            val hasExclude = brand.exclude.isNotEmpty() && brand.exclude.any { ex -> lower.contains(ex.lowercase()) }
            !hasExclude && brand.keywords.any { kw -> lower.contains(kw.lowercase()) }
        }.distinctBy { it.streamUrl }.sortedBy { it.name }
    }
}