package com.vyan.xtreamplayer.core

import android.content.Context
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

    private val resRegex = Regex("RESOLUTION=(\\d+x\\d+)")
    private val bwRegex = Regex("BANDWIDTH=(\\d+)")

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

            val trimmed = body.trim()
            if (trimmed.startsWith("#EXTM3U") || trimmed.contains("#EXTINF:") || trimmed.contains("#EXT-X-STREAM-INF:")) {
                return@withContext parseM3uContent(body, source)
            } else if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return@withContext parseGenericJson(body, source)
            } else {
                return@withContext parseM3uContent(body, source)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return@withContext emptyList()
        }
    }

    private fun parseGenericJson(jsonStr: String, source: ExtremeSourceConfig): List<ExtremeChannel> {
        val channels = mutableListOf<ExtremeChannel>()
        try {
            val trimmed = jsonStr.trim()
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                parseJsonArrayRecursive(arr, channels, source)
            } else if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                parseJsonObjectRecursive(obj, channels, source)
            }
        } catch (_: Throwable) { }
        return channels
    }

    private fun parseJsonArrayRecursive(
        arr: JSONArray,
        channels: MutableList<ExtremeChannel>,
        source: ExtremeSourceConfig,
        parentName: String = "",
        parentLogo: String = "",
        parentGroup: String = ""
    ) {
        for (i in 0 until arr.length()) {
            val item = arr.opt(i)
            if (item is JSONObject) parseJsonObjectRecursive(item, channels, source, parentName, parentLogo, parentGroup)
            else if (item is JSONArray) parseJsonArrayRecursive(item, channels, source, parentName, parentLogo, parentGroup)
        }
    }

    private fun parseJsonObjectRecursive(
        obj: JSONObject,
        channels: MutableList<ExtremeChannel>,
        source: ExtremeSourceConfig,
        parentName: String = "",
        parentLogo: String = "",
        parentGroup: String = ""
    ) {
        val streamUrl = listOf("stream_url", "playbackurl", "channel_url", "videourl", "mpd", "url", "mpd_url", "link", "play_url", "m3u8")
            .map { obj.optString(it, "") }
            .firstOrNull { it.isNotBlank() && it != "null" } ?: ""

        val localName = listOf("channel_name", "channelname", "name", "title", "content_title", "match_name", "event_name")
            .map { obj.optString(it, "") }
            .firstOrNull { it.isNotBlank() && it != "null" } ?: parentName

        val localLogo = listOf("channel_image", "logo", "cover_image", "imageurl", "src", "image", "thumbnail", "poster_image")
            .map { obj.optString(it, "") }
            .firstOrNull { it.isNotBlank() && it != "null" } ?: parentLogo

        val localGroup = listOf("category", "event_category", "group", "stage")
            .map { obj.optString(it, "") }
            .firstOrNull { it.isNotBlank() && it != "null" } ?: parentGroup

        // Only add if URL starts with http to prevent crashes from "Not Available" strings
        if (streamUrl.startsWith("http", ignoreCase = true)) {
            val cookie = obj.optString("cookie", "").trim()
            val rawKeyId = listOf("keyId", "key_id", "license_url", "clearkey", "license").map { obj.optString(it, "") }.firstOrNull { it.isNotBlank() } ?: ""
            val rawKey = obj.optString("key", "").let { if (it == "null") "" else it }

            var finalKeyId = rawKeyId
            var finalKey = rawKey

            if (finalKeyId.isEmpty() && rawKey.contains(":")) {
                finalKeyId = rawKey.substringBefore(":")
                finalKey = rawKey.substringAfter(":")
            }

            val headerMap = mutableMapOf<String, String>()
            val headersObj = obj.optJSONObject("headers")
            if (headersObj != null) {
                headersObj.keys().forEach { k -> headerMap[k] = headersObj.optString(k) }
            }

            listOf("referer", "origin", "user-agent", "user_agent").forEach { k ->
                val v = obj.optString(k, "")
                if (v.isNotBlank() && v != "null") {
                    if (!k.equals("user-agent", ignoreCase = true) && !k.equals("user_agent", ignoreCase = true)) {
                        headerMap[k.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }] = v
                    }
                }
            }

            channels.add(
                ExtremeChannel(
                    id = streamUrl.hashCode().toString(),
                    name = localName.ifEmpty { "Channel" },
                    group = localGroup.ifEmpty { source.defaultCategory },
                    logo = localLogo.ifEmpty { source.image },
                    streamUrl = streamUrl,
                    userAgent = obj.optString("user_agent", obj.optString("user-agent", source.defaultUserAgent)),
                    cookie = cookie,
                    cookieExpires = extractCookieExpiry(cookie),
                    keyId = finalKeyId,
                    key = finalKey,
                    sourceName = source.name,
                    headers = headerMap.toMap()
                )
            )
        }

        obj.keys().forEach { key ->
            val child = obj.opt(key)
            if (child is JSONObject) {
                val childName = if (localName.isNotBlank() && key !in listOf("headers", "drm", "license", "hindi", "english")) {
                    "$localName - ${key.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }}"
                } else if (key in listOf("hindi", "english")) {
                    "$localName - ${key.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }}"
                } else {
                    localName
                }
                parseJsonObjectRecursive(child, channels, source, childName, localLogo, localGroup)
            } else if (child is JSONArray) {
                parseJsonArrayRecursive(child, channels, source, localName, localLogo, localGroup)
            }
        }
    }

    private fun extractAttribute(line: String, attr: String): String {
        val searchStr = "$attr=\""
        val start = line.indexOf(searchStr)
        if (start == -1) return ""
        val valueStart = start + searchStr.length
        val end = line.indexOf("\"", valueStart)
        if (end == -1) return ""
        return line.substring(valueStart, end)
    }

    private fun parseM3uContent(m3uStr: String, source: ExtremeSourceConfig): List<ExtremeChannel> {
        val channels = mutableListOf<ExtremeChannel>()
        val lines = m3uStr.lines()

        var currentName = ""; var currentId = ""; var currentLogo = ""; var currentGroup = source.defaultCategory
        var currentKeyId = ""; var currentKey = ""; var currentUa = source.defaultUserAgent; var currentCookie = ""
        val currentHeaders = mutableMapOf<String, String>()

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#EXTINF:")) {
                currentHeaders.clear()
                currentId = extractAttribute(line, "tvg-id")
                currentLogo = extractAttribute(line, "tvg-logo")
                val grp = extractAttribute(line, "group-title")
                currentGroup = grp.ifEmpty { source.defaultCategory }
                currentName = line.substringAfterLast(",").trim().ifEmpty { "Channel" }

            } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val resMatch = resRegex.find(line)
                val bwMatch = bwRegex.find(line)
                val res = resMatch?.groupValues?.get(1)
                val bw = bwMatch?.groupValues?.get(1)?.toLongOrNull()?.let { "${it / 1000} Kbps" }

                currentName = when {
                    res != null && bw != null -> "Quality: $res ($bw)"
                    res != null -> "Quality: $res"
                    bw != null -> "Quality: $bw"
                    else -> "Stream Variant"
                }
                currentGroup = source.defaultCategory

            } else if (line.startsWith("#KODIPROP:inputstream.adaptive.license_key=")) {
                val keyData = line.substringAfter("=").trim()
                if (keyData.startsWith("http", ignoreCase = true)) {
                    currentKeyId = keyData
                } else if (keyData.contains(":")) {
                    currentKeyId = keyData.substringBefore(":")
                    currentKey = keyData.substringAfter(":")
                }

            } else if (line.startsWith("#EXTVLCOPT:")) {
                val opt = line.substringAfter("#EXTVLCOPT:")
                if (opt.startsWith("http-user-agent=", ignoreCase = true)) {
                    currentUa = opt.substringAfter("=").trim()
                    currentHeaders["User-Agent"] = currentUa
                } else if (opt.startsWith("http-referrer=", ignoreCase = true)) {
                    currentHeaders["Referer"] = opt.substringAfter("=").trim()
                } else if (opt.startsWith("http-cookie=", ignoreCase = true)) {
                    currentCookie = opt.substringAfter("=").trim()
                    currentHeaders["Cookie"] = currentCookie
                } else if (opt.startsWith("http-extra-headers=", ignoreCase = true)) {
                    val extra = opt.substringAfter("=").trim()
                    val hParts = extra.split(":", limit = 2)
                    if (hParts.size == 2) currentHeaders[hParts[0].trim()] = hParts[1].trim()
                }

            } else if (!line.startsWith("#") && line.startsWith("http", ignoreCase = true)) {
                val parts = line.split("|")
                val cleanUrl = parts[0].trim()

                if (parts.size > 1) {
                    val appends = parts[1].split("&")
                    for (append in appends) {
                        val kv = append.split("=", limit = 2)
                        if (kv.size == 2) {
                            val k = kv[0].trim().lowercase()
                            val v = kv[1].trim()
                            when (k) {
                                "cookie" -> { currentCookie = v; currentHeaders["Cookie"] = v }
                                "user-agent" -> { currentUa = v; currentHeaders["User-Agent"] = v }
                                "referer" -> currentHeaders["Referer"] = v
                                "origin" -> currentHeaders["Origin"] = v
                                else -> currentHeaders[kv[0].trim()] = v
                            }
                        }
                    }
                }

                if (!currentCookie.startsWith("error", ignoreCase = true) && (source.filterKeyword == null || currentName.contains(source.filterKeyword, ignoreCase = true))) {
                    channels.add(
                        ExtremeChannel(
                            id = currentId.ifEmpty { cleanUrl.hashCode().toString() }, name = currentName.ifEmpty { "Channel ${channels.size + 1}" },
                            group = currentGroup, logo = currentLogo, streamUrl = cleanUrl, userAgent = currentUa, cookie = currentCookie,
                            cookieExpires = extractCookieExpiry(currentCookie), keyId = currentKeyId, key = currentKey, sourceName = source.name,
                            headers = currentHeaders.toMap()
                        )
                    )
                }
                currentName = ""; currentId = ""; currentLogo = ""; currentGroup = source.defaultCategory
                currentKeyId = ""; currentKey = ""; currentCookie = ""; currentUa = source.defaultUserAgent
                currentHeaders.clear()
            }
        }
        return channels
    }
}