package com.vyan.xtreamplayer.core

import android.content.Context
import com.vyan.xtreamplayer.models.M3uChannel
import com.vyan.xtreamplayer.stream.PlaylistImportParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

enum class SourceType {
    JSON_DIRECT, JSON_WRAPPED, M3U_DIRECT, M3U_INLINE_DRM
}

data class ExtremeSourceConfig(
    val id: String,
    val name: String,
    val category: String = "General",
    val url: String,
    val type: SourceType,
    val defaultUserAgent: String = "OTT Navigator",
    val defaultCategory: String = "Live TV",
    val filterKeyword: String? = null,
    val image: String = "https://upload.wikimedia.org/wikipedia/commons/thumb/5/50/JioTV_logo.svg/1024px-JioTV_logo.svg.png"
)

data class ExtremeChannel(
    val id: String, val name: String, val group: String, val logo: String,
    val streamUrl: String, val userAgent: String, val cookie: String = "",
    val cookieExpires: String = "", val keyId: String = "", val key: String = "",
    val sourceName: String = "", val headers: Map<String, String> = emptyMap()
) {
    val isDrmProtected: Boolean get() = keyId.isNotEmpty() && key.isNotEmpty()
}

object ExtremeSourceRegistry {
    const val REMOTE_CONFIG_URL = "https://app-source-api.dbprojects.workers.dev/"

    var ALL_SOURCES: List<ExtremeSourceConfig> = emptyList()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun loadMasterSources(context: Context): List<ExtremeSourceConfig> = withContext(Dispatchers.IO) {
        if (ALL_SOURCES.isNotEmpty()) {
            return@withContext ALL_SOURCES
        }
        try {
            val request = Request.Builder()
                .url(REMOTE_CONFIG_URL)
                .header("User-Agent", "CloudPlay-Android")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val remoteBody = response.body?.string()?.trim()
                if (!remoteBody.isNullOrEmpty()) {
                    val freshSources = parseMasterSourcesJson(remoteBody)
                    if (freshSources.isNotEmpty()) {
                        ALL_SOURCES = freshSources
                        return@withContext freshSources
                    }
                }
            }
        } catch (_: Exception) { }
        return@withContext emptyList()
    }

    // --- Unified Custom Source Logic ---

    fun getCustomSources(context: Context): List<ExtremeSourceConfig> {
        val sharedPrefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val jsonStr = sharedPrefs.getString("custom_extreme_sources", "[]") ?: "[]"
        val list = mutableListOf<ExtremeSourceConfig>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    ExtremeSourceConfig(
                        id = obj.optString("id"),
                        name = obj.optString("name", "Custom Source"),
                        category = obj.optString("category", "Custom"),
                        url = obj.optString("url", ""),
                        type = if (obj.optString("type", "M3U") == "M3U") SourceType.M3U_DIRECT else SourceType.JSON_WRAPPED,
                        image = "https://upload.wikimedia.org/wikipedia/commons/thumb/5/50/JioTV_logo.svg/1024px-JioTV_logo.svg.png"
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    fun addCustomSource(context: Context, name: String, url: String, type: String) {
        val sharedPrefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val arr = JSONArray(sharedPrefs.getString("custom_extreme_sources", "[]") ?: "[]")
        val newObj = JSONObject().apply {
            put("id", "custom_${System.currentTimeMillis()}")
            put("name", name.trim())
            put("url", url.trim())
            put("type", type)
            put("category", "Custom")
        }
        arr.put(newObj)
        sharedPrefs.edit().putString("custom_extreme_sources", arr.toString()).apply()
    }

    fun deleteCustomSource(context: Context, sourceId: String) {
        val sharedPrefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val arr = JSONArray(sharedPrefs.getString("custom_extreme_sources", "[]") ?: "[]")
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.optString("id") != sourceId) {
                newArr.put(obj)
            }
        }
        sharedPrefs.edit().putString("custom_extreme_sources", newArr.toString()).apply()
    }

    // -----------------------------------

    private fun parseMasterSourcesJson(jsonStr: String): List<ExtremeSourceConfig> {
        val list = mutableListOf<ExtremeSourceConfig>()
        try {
            val root = JSONObject(jsonStr)
            val sourcesArr = root.optJSONArray("sources") ?: JSONArray()

            for (i in 0 until sourcesArr.length()) {
                val obj = sourcesArr.getJSONObject(i)
                val typeStr = obj.optString("type", "M3U_INLINE_DRM")
                val resolvedType = try {
                    SourceType.valueOf(typeStr)
                } catch (_: Exception) {
                    SourceType.M3U_INLINE_DRM
                }

                list.add(
                    ExtremeSourceConfig(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        category = obj.optString("category", "General"),
                        url = obj.getString("url"),
                        type = resolvedType,
                        defaultUserAgent = obj.optString("defaultUserAgent", "OTT Navigator"),
                        defaultCategory = obj.optString("defaultCategory", "Live TV"),
                        image = obj.optString("image", "https://upload.wikimedia.org/wikipedia/commons/thumb/5/50/JioTV_logo.svg/1024px-JioTV_logo.svg.png")
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    private fun extractCookieExpiry(cookie: String): String {
        if (cookie.isBlank()) return ""
        val expIndex = cookie.indexOf("exp=")
        if (expIndex != -1) {
            val start = expIndex + 4
            if (start <= cookie.length) {
                val end = cookie.indexOf("~", start)
                val tsStr = if (end != -1) cookie.substring(start, end) else cookie.substring(start)
                return try {
                    val epoch = tsStr.toLong() * 1000L
                    val sdf = SimpleDateFormat("d/M/yyyy h:mm:ss a 'IST'", Locale.ENGLISH).apply { timeZone = TimeZone.getTimeZone("Asia/Kolkata") }
                    sdf.format(Date(epoch))
                } catch (_: Exception) { "" }
            }
        }
        return ""
    }

    suspend fun fetchChannels(source: ExtremeSourceConfig): List<ExtremeChannel> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(source.url)
                .header("User-Agent", source.defaultUserAgent.ifBlank { "OTT Navigator" })
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .header("Connection", "keep-alive")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return@withContext emptyList()
            }

            val body = response.body?.string() ?: ""
            response.close()

            if (body.isBlank()) return@withContext emptyList()

            val m3uChannels: List<M3uChannel> = try {
                PlaylistImportParser.parse(body)
            } catch (_: IllegalArgumentException) {
                emptyList()
            }

            m3uChannels
                .asSequence()
                .filter { source.filterKeyword == null || it.name.contains(source.filterKeyword, ignoreCase = true) }
                .map { it.toExtremeChannel(source) }
                .toList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyList()
        }
    }

    private fun M3uChannel.toExtremeChannel(source: ExtremeSourceConfig): ExtremeChannel {
        val effectiveUa = userAgent.ifBlank { source.defaultUserAgent }
        val effectiveGroup = group.ifBlank { source.defaultCategory }

        val isLicenseUrl = keyId.startsWith("http", ignoreCase = true)
        val finalKeyId = if (isLicenseUrl) "" else keyId.substringBefore(":", keyId)
        val finalKey = if (isLicenseUrl) "" else key.ifBlank { keyId.substringAfter(":", "") }

        val headerMap = mutableMapOf<String, String>()
        if (effectiveUa.isNotBlank()) headerMap["User-Agent"] = effectiveUa
        if (cookie.isNotBlank()) headerMap["Cookie"] = cookie

        return ExtremeChannel(
            id = tvgId.ifBlank { url.hashCode().toString() },
            name = name.ifBlank { "Channel" },
            group = effectiveGroup,
            logo = logo.ifBlank { source.image },
            streamUrl = url,
            userAgent = effectiveUa,
            cookie = cookie,
            cookieExpires = extractCookieExpiry(cookie),
            keyId = finalKeyId,
            key = finalKey,
            sourceName = source.name,
            headers = headerMap
        )
    }
}