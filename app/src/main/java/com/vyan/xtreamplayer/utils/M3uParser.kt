package com.vyan.xtreamplayer.utils

import android.content.Context
import android.net.Uri
import com.vyan.xtreamplayer.models.M3uChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object M3uParser {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // FIXED: Restored the original parse method so LiveTVScreen doesn't crash!
    // It now uses byteInputStream() to safely stream the string without OOM errors.
    fun parse(content: String): List<M3uChannel> {
        if (content.isBlank()) {
            throw IllegalArgumentException("Playlist is empty")
        }
        return parseStream(content.byteInputStream(Charsets.UTF_8))
    }

    // SAFELY parse a local file line-by-line without running out of RAM
    suspend fun parseLocalFile(context: Context, uri: Uri): List<M3uChannel> =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            resolver.openInputStream(uri)?.use { stream ->
                parseStream(stream)
            } ?: emptyList()
        }

    // SAFELY download and parse a network file line-by-line
    suspend fun parseNetworkUrl(url: String): List<M3uChannel> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            response.body?.byteStream()?.use { stream ->
                parseStream(stream)
            } ?: emptyList()
        }
    }

    private fun parseStream(inputStream: InputStream): List<M3uChannel> {
        val out = mutableListOf<M3uChannel>()

        var pendingName: String? = null
        var pendingLogo = ""
        var pendingGroup = ""
        var pendingTvgId = ""
        var pendingTvgName = ""

        fun resetPending() {
            pendingName = null
            pendingLogo = ""
            pendingGroup = ""
            pendingTvgId = ""
            pendingTvgName = ""
        }

        // useLines streams the text efficiently, preventing OutOfMemory crashes
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

                if (line.startsWith("#EXTGRP:")) {
                    pendingGroup = line.substring("#EXTGRP:".length).trim()
                    continue
                }

                // Unknown directive (e.g. #EXTVLCOPT) — ignore it
                if (line.startsWith("#")) continue

                // Plain URL line
                val url = line
                if (!looksLikeUrl(url)) continue

                out.add(
                    M3uChannel(
                        name = pendingName ?: url,
                        url = url,
                        logo = pendingLogo,
                        group = pendingGroup,
                        tvgId = pendingTvgId,
                        tvgName = pendingTvgName
                    )
                )
                resetPending()
            }
        }

        if (out.isEmpty()) {
            throw IllegalArgumentException("No channels found — is this a valid M3U playlist?")
        }
        return out
    }

    private fun looksLikeUrl(s: String): Boolean {
        val lower = s.lowercase()
        return lower.startsWith("http://") ||
                lower.startsWith("https://") ||
                lower.startsWith("rtmp://") ||
                lower.startsWith("rtmps://") ||
                lower.startsWith("rtsp://") ||
                lower.startsWith("udp://") ||
                lower.startsWith("rtp://") ||
                lower.startsWith("mms://") ||
                lower.startsWith("mmsh://")
    }

    private fun parseAttrs(input: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var s = input.trim()

        if (s.startsWith(":")) s = s.substring(1).trim()

        // Drop the leading numeric duration
        val durMatch = Regex("^-?\\d+(\\.\\d+)?").find(s)
        if (durMatch != null) {
            s = s.substring(durMatch.range.last + 1).trim()
        }

        // Matches key="value" | key='value' | key=value
        val re = Regex("""([a-zA-Z0-9_\-]+)=("([^"]*)"|'([^']*)'|([^\s,]+))""")
        re.findAll(s).forEach { match ->
            val key = match.groupValues[1].lowercase()
            val value = match.groupValues[3].ifEmpty {
                match.groupValues[4].ifEmpty { match.groupValues[5] }
            }
            result[key] = value
        }
        return result
    }
}