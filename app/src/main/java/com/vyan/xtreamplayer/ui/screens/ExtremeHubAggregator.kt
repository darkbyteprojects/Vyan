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
}