package com.vyan.iptv.stream

import android.content.Context
import android.net.Uri
import com.vyan.iptv.models.M3uChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import com.vyan.iptv.utils.NetworkClient

object PlaylistImportParser {

    private val client = NetworkClient.defaultClient

    // Added "mpd_url" and "dash_url" to support the new JSON formats
    private val URL_KEYS = listOf("m3u8", "stream_url", "url", "link", "play_url", "src", "file", "video_url", "source", "mpd_url", "dash_url")
    private val NAME_KEYS = listOf("title", "name", "match_name", "channel_name", "stream_display_name", "ch_name")
    private val LOGO_KEYS = listOf("logo", "icon", "poster_image", "stream_icon", "tvg-logo", "pic", "image")
    private val GROUP_KEYS = listOf("genre", "category", "stage", "group-title", "category_name", "group")
    private val ID_KEYS = listOf("id", "tvg-id", "stream_id", "channel_id")
    private val KEY_ID_KEYS = listOf("key_id", "keyId", "kid", "drm_id")
    private val KEY_KEYS = listOf("key", "k", "clearkey", "drm_key")
    private val COOKIE_KEYS = listOf("cookie", "cookies", "http-cookie")
    private val UA_KEYS = listOf("user_agent", "user-agent", "http-user-agent", "ua")

    fun parse(content: String): List<M3uChannel> {
        if (content.isBlank()) throw IllegalArgumentException("Playlist is empty")
        val trimmed = content.trim()
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) return parseUniversalJson(trimmed)
        return parseStream(content.byteInputStream(Charsets.UTF_8))
    }

    suspend fun parseLocalFile(context: Context, uri: Uri): List<M3uChannel> = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { parseStream(it) } ?: emptyList()
    }

    suspend fun parseNetworkUrl(url: String): List<M3uChannel> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            response.body?.byteStream()?.use { parseStream(it) } ?: emptyList()
        }
    }

    private fun parseUniversalJson(content: String): List<M3uChannel> {
        val out = mutableListOf<M3uChannel>()
        try {
            var jsonNode: Any? = null
            val trimmed = content.trim()

            if (trimmed.startsWith("[")) {
                val wrapped = Regex(""",\s*]\s*$""").replace(trimmed, "]")
                jsonNode = JSONArray(wrapped)
            } else if (trimmed.startsWith("{")) {
                jsonNode = JSONObject(trimmed)
            }

            extractChannelsRecursively(jsonNode, out)
        } catch (_: Exception) {}
        return out
    }

    private fun extractChannelsRecursively(
        node: Any?,
        out: MutableList<M3uChannel>,
        parentName: String = "",
        parentLogo: String = "",
        parentGroup: String = "",
        nodeKey: String = ""
    ) {
        when (node) {
            is JSONObject -> {
                val currentName = getFirstMatchingString(node, NAME_KEYS).ifBlank { parentName }
                val currentLogo = getFirstMatchingString(node, LOGO_KEYS).ifBlank { parentLogo }
                val currentGroup = getFirstMatchingString(node, GROUP_KEYS).ifBlank { parentGroup }

                val url = getFirstMatchingString(node, URL_KEYS)

                if (url.isNotBlank() && (url.startsWith("http", true) || url.endsWith(".m3u8", true) || url.endsWith(".ts", true) || url.endsWith(".mpd", true))) {
                    val finalName = if (nodeKey.isNotBlank() && currentName == parentName && !currentName.contains(nodeKey, true)) {
                        "$currentName (${nodeKey.replaceFirstChar { it.uppercase() }})"
                    } else {
                        currentName.ifBlank { "Unknown Channel" }
                    }

                    parseSingleChannelObject(node, url, finalName, currentLogo, currentGroup, out)
                }

                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = node.opt(key)
                    if (value is JSONObject || value is JSONArray) {
                        extractChannelsRecursively(value, out, currentName, currentLogo, currentGroup, key)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    extractChannelsRecursively(node.opt(i), out, parentName, parentLogo, parentGroup, nodeKey)
                }
            }
        }
    }

    private fun parseSingleChannelObject(
        obj: JSONObject,
        resolvedUrl: String,
        finalName: String,
        finalLogo: String,
        finalGroup: String,
        out: MutableList<M3uChannel>
    ) {
        val kId = getFirstMatchingString(obj, KEY_ID_KEYS)
        val k = getFirstMatchingString(obj, KEY_KEYS)
        // Extract license URL directly
        val licenseUrl = obj.optString("license_url", obj.optString("licenseUrl", obj.optString("drm_license_url", "")))

        val combinedKeyId = when {
            kId.isNotBlank() && k.isNotBlank() && !kId.contains(":") -> "$kId:$k"
            kId.isNotBlank() -> kId
            k.contains(":") -> k
            else -> ""
        }

        // Support for nested headers object
        val headersObj = obj.optJSONObject("headers") ?: obj.optJSONObject("http_headers")

        val referer = obj.optString("referer", obj.optString("Referer", "")).ifBlank {
            headersObj?.optString("referer", headersObj.optString("Referer", "")) ?: ""
        }
        val origin = obj.optString("origin", obj.optString("Origin", "")).ifBlank {
            headersObj?.optString("origin", headersObj.optString("Origin", "")) ?: ""
        }
        val ua = getFirstMatchingString(obj, UA_KEYS).ifBlank {
            headersObj?.let { getFirstMatchingString(it, UA_KEYS) } ?: ""
        }
        val cookie = getFirstMatchingString(obj, COOKIE_KEYS).ifBlank {
            headersObj?.let { getFirstMatchingString(it, COOKIE_KEYS) } ?: ""
        }

        var finalUrl = resolvedUrl
        val pipeParams = mutableListOf<String>()

        if (ua.isNotBlank()) pipeParams.add("User-Agent=${Uri.encode(ua)}")
        if (referer.isNotBlank()) pipeParams.add("Referer=${Uri.encode(referer)}")
        if (origin.isNotBlank()) pipeParams.add("Origin=${Uri.encode(origin)}")
        if (cookie.isNotBlank()) pipeParams.add("Cookie=${Uri.encode(cookie)}")
        if (kId.isNotBlank()) pipeParams.add("keyid=${Uri.encode(kId)}")
        if (k.isNotBlank()) pipeParams.add("key=${Uri.encode(k)}")
        if (licenseUrl.isNotBlank()) pipeParams.add("licenseurl=${Uri.encode(licenseUrl)}")

        if (pipeParams.isNotEmpty()) {
            val separator = if (finalUrl.contains("|")) "&" else "|"
            finalUrl = "$finalUrl$separator${pipeParams.joinToString("&")}"
        }

        out.add(
            M3uChannel(
                name = finalName,
                url = finalUrl,
                logo = finalLogo,
                group = finalGroup.ifBlank { "Uncategorized" },
                tvgId = getFirstMatchingString(obj, ID_KEYS),
                tvgName = finalName,
                userAgent = ua,
                cookie = cookie,
                keyId = combinedKeyId,
                key = k
            )
        )
    }

    private fun getFirstMatchingString(obj: JSONObject, possibleKeys: List<String>): String {
        for (key in possibleKeys) {
            val value = obj.optString(key, "")
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun parseStream(inputStream: InputStream): List<M3uChannel> {
        val out = mutableListOf<M3uChannel>()

        var pendingName: String? = null
        var pendingLogo = ""
        var pendingGroup = ""
        var pendingTvgId = ""
        var pendingTvgName = ""
        var pendingLicenseKey = ""
        var pendingLicenseType = ""
        var pendingUserAgent = ""
        var pendingCookie = ""
        var pendingReferer = ""
        var pendingOrigin = ""

        BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#EXTM3U")) continue

                if (line.startsWith("#EXTINF")) {
                    var inQuotes = false
                    var firstCommaIdx = -1
                    var separatorCommaIdx = -1

                    // Smart Comma Detector: Scan the line to find the REAL comma separating attributes from the title
                    for (i in "#EXTINF".length until line.length) {
                        if (line[i] == '"') {
                            inQuotes = !inQuotes
                        } else if (line[i] == ',' && !inQuotes) {
                            if (firstCommaIdx == -1) firstCommaIdx = i

                            // Check if the text after this comma looks like an attribute (e.g., tvg-logo=)
                            val nextPart = line.substring(i + 1)
                            val attrRegex = Regex("""^\s*[a-zA-Z0-9_\-]+=""")

                            if (attrRegex.containsMatchIn(nextPart)) {
                                // This is a fake comma (like after -1). Keep scanning.
                                continue
                            } else {
                                // No attribute follows this comma. This is the REAL title separator.
                                separatorCommaIdx = i
                                break
                            }
                        }
                    }

                    // Fallback to the first comma if no perfect separator was found
                    if (separatorCommaIdx == -1) {
                        separatorCommaIdx = firstCommaIdx
                    }

                    val attrPart = if (separatorCommaIdx > 0) line.substring("#EXTINF".length, separatorCommaIdx) else line.substring("#EXTINF".length)
                    val namePart = if (separatorCommaIdx > 0) line.substring(separatorCommaIdx + 1).trim() else ""

                    val attrs = parseAttrs(attrPart)
                    pendingTvgId = attrs["tvg-id"] ?: ""
                    pendingTvgName = attrs["tvg-name"] ?: ""
                    pendingLogo = attrs["tvg-logo"] ?: ""
                    pendingGroup = attrs["group-title"] ?: ""
                    pendingName = if (namePart.isNotEmpty()) namePart else if (pendingTvgName.isNotEmpty()) pendingTvgName else "Unknown"
                    continue
                }

                when {
                    line.startsWith("#EXTGRP:") -> pendingGroup = line.substring("#EXTGRP:".length).trim()
                    line.startsWith("#KODIPROP:inputstream.adaptive.license_type=") -> pendingLicenseType = line.substringAfter("=").trim()
                    line.startsWith("#KODIPROP:inputstream.adaptive.license_key=") -> pendingLicenseKey = line.substringAfter("=").trim()
                    line.startsWith("#EXTVLCOPT:http-user-agent=") -> pendingUserAgent = line.substringAfter("=").trim()
                    line.startsWith("#EXTVLCOPT:http-referrer=") -> pendingReferer = line.substringAfter("=").trim()
                    line.startsWith("#EXTVLCOPT:http-cookie=") -> pendingCookie = line.substringAfter("=").trim()
                    line.startsWith("#EXTVLCOPT:http-origin=") -> pendingOrigin = line.substringAfter("=").trim()
                    line.startsWith("#EXTVLCOPT:http-extra-headers=") -> {
                        val extra = line.substringAfter("=").trim()
                        if (extra.contains(":", true)) {
                            val parts = extra.split(":", limit = 2)
                            val hKey = parts[0].trim().lowercase()
                            val hVal = parts[1].trim()
                            if (hKey == "origin") pendingOrigin = hVal
                            if (hKey == "referer") pendingReferer = hVal
                        }
                    }
                    line.startsWith("#EXTHTTP:") -> {
                        try {
                            val json = JSONObject(line.substringAfter("#EXTHTTP:").trim())
                            json.keys().forEach { k ->
                                val v = json.getString(k)
                                when (k.lowercase()) {
                                    "cookie" -> if (pendingCookie.isBlank()) pendingCookie = v
                                    "origin" -> if (pendingOrigin.isBlank()) pendingOrigin = v
                                    "referer", "referrer" -> if (pendingReferer.isBlank()) pendingReferer = v
                                    "user-agent" -> if (pendingUserAgent.isBlank()) pendingUserAgent = v
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }

                if (line.startsWith("#") || (!line.startsWith("http://", true) && !line.startsWith("https://", true))) continue

                var finalUrl = line
                val pipeParams = mutableListOf<String>()

                if (pendingUserAgent.isNotBlank()) pipeParams.add("User-Agent=${Uri.encode(pendingUserAgent)}")
                if (pendingReferer.isNotBlank()) pipeParams.add("Referer=${Uri.encode(pendingReferer)}")
                if (pendingOrigin.isNotBlank()) pipeParams.add("Origin=${Uri.encode(pendingOrigin)}")
                if (pendingCookie.isNotBlank()) pipeParams.add("Cookie=${Uri.encode(pendingCookie)}")
                if (pendingLicenseType.isNotBlank()) pipeParams.add("licensetype=${Uri.encode(pendingLicenseType)}")

                if (pendingLicenseKey.isNotBlank()) {
                    if (pendingLicenseKey.startsWith("http", ignoreCase = true)) {
                        pipeParams.add("licenseurl=${Uri.encode(pendingLicenseKey)}")
                    } else if (pendingLicenseKey.contains(":")) {
                        pipeParams.add("keyid=${Uri.encode(pendingLicenseKey.substringBefore(":"))}")
                        pipeParams.add("key=${Uri.encode(pendingLicenseKey.substringAfter(":"))}")
                    } else {
                        pipeParams.add("licenseurl=${Uri.encode(pendingLicenseKey)}")
                    }
                }

                if (pipeParams.isNotEmpty()) {
                    val separator = if (finalUrl.contains("|")) "&" else "|"
                    finalUrl = "$finalUrl$separator${pipeParams.joinToString("&")}"
                }

                out.add(
                    M3uChannel(
                        name = pendingName ?: finalUrl,
                        url = finalUrl,
                        logo = pendingLogo,
                        group = pendingGroup,
                        tvgId = pendingTvgId,
                        tvgName = pendingTvgName,
                        userAgent = pendingUserAgent,
                        cookie = pendingCookie,
                        keyId = pendingLicenseKey
                    )
                )

                pendingName = null
                pendingLogo = ""; pendingGroup = ""; pendingTvgId = ""; pendingTvgName = ""
                pendingLicenseKey = ""; pendingLicenseType = ""; pendingUserAgent = ""; pendingCookie = ""; pendingReferer = ""; pendingOrigin = ""
            }
        }
        if (out.isEmpty()) throw IllegalArgumentException("No channels found. Invalid format.")
        return out
    }

    private fun parseAttrs(input: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var s = input.trim()
        if (s.startsWith(":")) s = s.substring(1).trim()
        val durMatch = Regex("^-?\\d+(\\.\\d+)?").find(s)
        if (durMatch != null) s = s.substring(durMatch.range.last + 1).trim()

        val re = Regex("""([a-zA-Z0-9_\-]+)=("([^"]*)"|'([^']*)'|([^\s,]+))""")
        re.findAll(s).forEach { match ->
            val key = match.groupValues[1].lowercase()
            val value = match.groupValues[3].ifEmpty { match.groupValues[4].ifEmpty { match.groupValues[5] } }
            result[key] = value
        }
        return result
    }
}