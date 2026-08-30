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
        val wrapped = if (content.startsWith("[")) content else "[$content]"
        val jsonArray = try { JSONArray(Regex(""",\s*]\s*$""").replace(wrapped, "]")) } catch (e: Exception) { return emptyList() }

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.optJSONObject(i) ?: continue
            val url = obj.optString("url", "")
            if (!url.startsWith("http", true)) continue

            // Parse keyId/key or extract from "clearkey" field
            val ck = obj.optString("clearkey", "")
            var kId = obj.optString("keyId", "")
            var k = obj.optString("key", "")
            if (kId.isBlank() && ck.contains(":")) {
                val p = ck.split(":")
                kId = p[0].trim()
                k = p[1].trim()
            }

            out.add(
                M3uChannel(
                    name = obj.optString("name", "Unknown").ifBlank { "Unknown" },
                    url = url,
                    logo = obj.optString("logo", ""),
                    group = obj.optString("category", ""),
                    tvgId = obj.optString("id", ""),
                    tvgName = obj.optString("name", ""),
                    userAgent = "",
                    cookie = obj.optString("cookie", ""),
                    keyId = kId,
                    key = k,
                    format = PlaylistFormat.FORMAT_3_JSON_CLEARKEY
                )
            )
        }
        return out
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
                if (line.startsWith("#EXTHTTP:")) {
                    try {
                        val json = JSONObject(line.substringAfter("#EXTHTTP:").trim())
                        if (json.has("cookie")) pendingCookie = json.getString("cookie")
                    } catch (e: Exception) {}
                    continue
                }

                if (line.startsWith("#")) continue

                val url = line
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) continue

                out.add(
                    M3uChannel(
                        name = pendingName ?: url,
                        url = url,
                        logo = pendingLogo,
                        group = pendingGroup,
                        tvgId = pendingTvgId,
                        tvgName = pendingTvgName,
                        userAgent = pendingUserAgent,
                        cookie = pendingCookie,
                        keyId = pendingLicenseKey,
                        format = PlaylistFormat.UNKNOWN
                    )
                )
                pendingName = null
                pendingLogo = ""; pendingGroup = ""; pendingTvgId = ""; pendingTvgName = ""
                pendingLicenseKey = ""; pendingUserAgent = ""; pendingCookie = ""
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