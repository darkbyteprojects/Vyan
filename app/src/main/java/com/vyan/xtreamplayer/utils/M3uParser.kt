package com.vyan.xtreamplayer.utils

import android.content.Context
import android.net.Uri
import com.vyan.xtreamplayer.models.M3uChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object M3uParser {

    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    fun parse(content: String): List<M3uChannel> {
        if (content.isBlank()) throw IllegalArgumentException("Playlist is empty")
        val trimmed = content.trim()
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) return parseFormat3Json(trimmed)
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

    private fun parseFormat3Json(content: String): List<M3uChannel> {
        val out = mutableListOf<M3uChannel>()
        try {
            val trimmed = content.trim()
            if (trimmed.startsWith("[")) {
                val wrapped = Regex(""",\s*]\s*$""").replace(trimmed, "]")
                parseJsonArray(JSONArray(wrapped), out)
            } else if (trimmed.startsWith("{")) {
                val jsonRoot = JSONObject(trimmed)
                if (jsonRoot.has("channels") && jsonRoot.optJSONArray("channels") != null) {
                    parseJsonArray(jsonRoot.getJSONArray("channels"), out)
                } else if (jsonRoot.has("result") && jsonRoot.optJSONArray("result") != null) {
                    parseJsonArray(jsonRoot.getJSONArray("result"), out)
                } else {
                    // Iterates over root keys like "sony-hd", "sab-hd"
                    val keys = jsonRoot.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val obj = jsonRoot.optJSONObject(key) ?: continue
                        parseJsonObject(obj, out)
                    }
                }
            }
        } catch (_: Exception) {}
        return out
    }

    private fun parseJsonArray(jsonArray: JSONArray, out: MutableList<M3uChannel>) {
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.optJSONObject(i) ?: continue
            parseJsonObject(obj, out)
        }
    }

    private fun parseJsonObject(obj: JSONObject, out: MutableList<M3uChannel>) {
        // FIX: Check "m3u8" first, since your JSON uses "m3u8" as the primary stream key
        val url = obj.optString("m3u8", obj.optString("url", obj.optString("stream_url", "")))
        if (!url.startsWith("http", true)) return

        val kId = obj.optString("keyId", obj.optString("key_id", ""))
        val k = obj.optString("key", "")
        val cookie = obj.optString("cookie", "")
        val ck = obj.optString("clearkey", "")

        val combinedKeyId = when {
            kId.isNotBlank() && k.isNotBlank() && !kId.contains(":") -> "$kId:$k"
            kId.isNotBlank() -> kId
            ck.contains(":") -> ck
            else -> ""
        }

        out.add(
            M3uChannel(
                // Map "title" first since your JSON uses "title" for channel names
                name = obj.optString("title", obj.optString("name", "Unknown")).ifBlank { "Unknown" },
                url = url,
                logo = obj.optString("logo", ""),
                // Map "genre" first since your JSON uses "genre"
                group = obj.optString("genre", obj.optString("category", obj.optString("group-title", "Sports"))),
                tvgId = obj.optString("id", ""),
                tvgName = obj.optString("title", obj.optString("name", "")),
                userAgent = "",
                cookie = cookie,
                keyId = combinedKeyId,
                key = k
            )
        )
    }

    private fun parseStream(inputStream: InputStream): List<M3uChannel> {
        val out = mutableListOf<M3uChannel>()

        var pendingName: String? = null
        var pendingLogo = ""
        var pendingGroup = ""
        var pendingTvgId = ""
        var pendingTvgName = ""
        var pendingLicenseKey = ""
        var pendingUserAgent = ""
        var pendingCookie = ""
        var pendingReferer = ""
        var pendingOrigin = ""

        BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#EXTM3U")) continue

                if (line.startsWith("#EXTINF")) {
                    val commaIdx = line.indexOf(',')
                    val attrPart = if (commaIdx > 0) line.substring("#EXTINF".length, commaIdx) else line.substring("#EXTINF".length)
                    val namePart = if (commaIdx > 0) line.substring(commaIdx + 1).trim() else ""

                    val attrs = parseAttrs(attrPart)
                    pendingTvgId = attrs["tvg-id"] ?: ""
                    pendingTvgName = attrs["tvg-name"] ?: ""
                    pendingLogo = attrs["tvg-logo"] ?: ""
                    pendingGroup = attrs["group-title"] ?: ""
                    pendingName = if (namePart.isNotEmpty()) namePart else if (pendingTvgName.isNotEmpty()) pendingTvgName else "Unknown"
                    continue
                }

                if (line.startsWith("#EXTGRP:")) { pendingGroup = line.substring("#EXTGRP:".length).trim(); continue }
                if (line.startsWith("#KODIPROP:inputstream.adaptive.license_key=")) { pendingLicenseKey = line.substringAfter("=").trim(); continue }
                if (line.startsWith("#EXTVLCOPT:http-user-agent=")) { pendingUserAgent = line.substringAfter("=").trim(); continue }
                if (line.startsWith("#EXTVLCOPT:http-referrer=")) { pendingReferer = line.substringAfter("=").trim(); continue }
                if (line.startsWith("#EXTVLCOPT:http-cookie=")) { pendingCookie = line.substringAfter("=").trim(); continue }

                if (line.startsWith("#EXTVLCOPT:http-extra-headers=")) {
                    val extra = line.substringAfter("=").trim()
                    if (extra.contains(":", true)) {
                        val parts = extra.split(":", limit = 2)
                        val hKey = parts[0].trim().lowercase()
                        val hVal = parts[1].trim()
                        if (hKey == "origin") pendingOrigin = hVal
                        if (hKey == "referer") pendingReferer = hVal
                    }
                    continue
                }

                if (line.startsWith("#EXTHTTP:")) {
                    try {
                        val json = JSONObject(line.substringAfter("#EXTHTTP:").trim())
                        json.keys().forEach { k ->
                            val lk = k.lowercase()
                            val v = json.getString(k)
                            when (lk) {
                                "cookie" -> if (pendingCookie.isBlank()) pendingCookie = v
                                "origin" -> if (pendingOrigin.isBlank()) pendingOrigin = v
                                "referer" -> if (pendingReferer.isBlank()) pendingReferer = v
                                "user-agent" -> if (pendingUserAgent.isBlank()) pendingUserAgent = v
                            }
                        }
                    } catch (e: Exception) {}
                    continue
                }

                if (line.startsWith("#")) continue

                val url = line
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) continue

                var finalUrl = url
                if (!finalUrl.contains("|")) {
                    val pipeParams = mutableListOf<String>()
                    if (pendingUserAgent.isNotBlank()) pipeParams.add("User-Agent=$pendingUserAgent")
                    if (pendingReferer.isNotBlank()) pipeParams.add("Referer=$pendingReferer")
                    if (pendingOrigin.isNotBlank()) pipeParams.add("Origin=$pendingOrigin")
                    if (pendingCookie.isNotBlank()) pipeParams.add("Cookie=$pendingCookie")

                    if (pipeParams.isNotEmpty()) {
                        finalUrl = "$finalUrl|${pipeParams.joinToString("&")}"
                    }
                }

                out.add(
                    M3uChannel(
                        name = pendingName ?: url,
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
                pendingLicenseKey = ""; pendingUserAgent = ""; pendingCookie = ""; pendingReferer = ""; pendingOrigin = ""
            }
        }
        if (out.isEmpty()) throw IllegalArgumentException("No channels found.")
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