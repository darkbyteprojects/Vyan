package com.vyan.xtreamplayer.utils

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vyan.xtreamplayer.core.ExtremeSourceConfig
import com.vyan.xtreamplayer.core.ExtremeSourceRegistry
import com.vyan.xtreamplayer.ui.screens.ExtremeHubAggregator
import org.json.JSONArray

class ExtremeSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            val prefs = applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            val selectedIds = prefs.getStringSet("selected_extreme_sources", null) ?: ExtremeSourceRegistry.ALL_SOURCES.map { it.id }.toSet()

            val customJsonStr = prefs.getString("custom_extreme_sources", "[]") ?: "[]"
            val customConfigsList = mutableListOf<ExtremeSourceConfig>()
            try {
                val arr = JSONArray(customJsonStr)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val gId = obj.optString("groupId", "")
                    customConfigsList.add(
                        ExtremeSourceConfig(
                            id = obj.optString("id"),
                            name = obj.optString("name"),
                            category = "Custom",
                            groupId = if (gId.isNotBlank()) gId else null,
                            url = obj.optString("url"),
                            type = if (obj.optString("type", "M3U") == "M3U") com.vyan.xtreamplayer.core.SourceType.M3U_DIRECT else com.vyan.xtreamplayer.core.SourceType.JSON_WRAPPED
                        )
                    )
                }
            } catch (_: Exception) {}

            val allConfigs = ExtremeSourceRegistry.ALL_SOURCES + customConfigsList
            val activeConfigs = allConfigs.filter { selectedIds.contains(it.id) || it.id.startsWith("custom_") }
            ExtremeHubAggregator.syncSources(activeConfigs)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}