package com.vyan.xtreamplayer.utils

import com.vyan.xtreamplayer.core.ExtremeChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

object ExtremeStreamChecker {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun isStreamAlive(channel: ExtremeChannel): Boolean = withContext(Dispatchers.IO) {
        try {
            val rawUrl = channel.streamUrl
            if (rawUrl.isBlank()) return@withContext false

            val cleanUrl = rawUrl.substringBefore("|").trim()
            if (!cleanUrl.startsWith("http", ignoreCase = true)) {
                return@withContext false
            }

            // Ensure Ktor proxy is active before checking
            LocalStreamProxy.start()

            val headersMap = buildRequestHeaders(channel, rawUrl)
            // THE FIX: Added "UNI" as the required lane parameter
            val proxyUrl = LocalStreamProxy.createProxyUrl(cleanUrl, headersMap, "UNI")

            checkProxyStream(proxyUrl)
        } catch (_: Exception) {
            false
        }
    }

    private fun buildRequestHeaders(channel: ExtremeChannel, rawUrl: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        map["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:154.0) Gecko/20100101 Firefox/154.0"
        map["Accept"] = "*/*"
        map["Connection"] = "keep-alive"

        if (channel.cookie.isNotBlank()) {
            map["Cookie"] = channel.cookie
        }
        map.putAll(channel.headers)

        // Parse trailing M3U headers (e.g., |User-Agent=...&Referer=...)
        if (rawUrl.contains("|")) {
            val headerPart = rawUrl.substringAfter("|")
            headerPart.split("&").forEach { pair ->
                val kv = pair.split("=", limit = 2)
                if (kv.size == 2) {
                    map[kv[0].trim()] = kv[1].trim()
                }
            }
        }

        val lowerUrl = rawUrl.lowercase()
        when {
            lowerUrl.contains("sonymtmnew") || lowerUrl.contains("sonydaimenew") || lowerUrl.contains("akamaized.net") || lowerUrl.contains("sony") -> {
                if (!map.containsKey("Origin")) map["Origin"] = "https://www.sonyliv.com/"
                if (!map.containsKey("Referer")) map["Referer"] = "https://www.sonyliv.com/"
            }
            lowerUrl.contains("jiotv") || lowerUrl.contains("jio.com") -> {
                if (!map.containsKey("Origin")) map["Origin"] = "https://www.jiotv.com/"
                if (!map.containsKey("Referer")) map["Referer"] = "https://www.jiotv.com/"
            }
            lowerUrl.contains("tataplay") || lowerUrl.contains("watch.tataplay.com") -> {
                if (!map.containsKey("Origin")) map["Origin"] = "https://watch.tataplay.com/"
                if (!map.containsKey("Referer")) map["Referer"] = "https://watch.tataplay.com/"
            }
            lowerUrl.contains("hotstar") -> {
                if (!map.containsKey("Origin")) map["Origin"] = "https://www.hotstar.com/"
                if (!map.containsKey("Referer")) map["Referer"] = "https://www.hotstar.com/"
            }
        }
        return map
    }

    private fun checkProxyStream(proxyUrl: String): Boolean {
        var response: Response? = null
        return try {
            val request = Request.Builder().url(proxyUrl).build()
            response = client.newCall(request).execute()

            if (!response.isSuccessful) return false

            val contentType = response.header("Content-Type", "")?.lowercase() ?: ""
            if (contentType.contains("text/html") || contentType.contains("application/json")) {
                return false
            }

            val body = response.body?.string()?.trim() ?: ""
            if (!body.startsWith("#EXTM3U") || body.contains("<html", ignoreCase = true)) {
                return false
            }

            val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val firstSegmentOrSubPlaylist = lines.firstOrNull { !it.startsWith("#") } ?: return false

            verifyChunkBinaryStream(firstSegmentOrSubPlaylist)
        } catch (_: Exception) {
            false
        } finally {
            response?.close()
        }
    }

    private fun verifyChunkBinaryStream(proxyChunkUrl: String): Boolean {
        var chunkResp: Response? = null
        return try {
            // THE FIX: Add the Range header to mimic ExoPlayer and bypass CDN bot checks
            val chunkReq = Request.Builder()
                .url(proxyChunkUrl)
                .header("Range", "bytes=0-2048")
                .build()

            chunkResp = client.newCall(chunkReq).execute()

            // CDNs return 206 Partial Content when a Range header is used successfully
            if (!chunkResp.isSuccessful && chunkResp.code != 206) return false

            val contentType = chunkResp.header("Content-Type", "")?.lowercase() ?: ""
            if (contentType.contains("text/html") || contentType.contains("application/json")) {
                return false
            }

            if (contentType.contains("mpegurl") || proxyChunkUrl.contains(".m3u8")) {
                val subManifest = chunkResp.body?.string() ?: ""
                chunkResp.close()

                val subSegment = subManifest.lines().map { it.trim() }.firstOrNull { !it.startsWith("#") && it.isNotEmpty() } ?: return false
                return verifyChunkBinaryStream(subSegment)
            }

            val inputStream = chunkResp.body?.byteStream() ?: return false
            val buffer = ByteArray(2048)
            var totalRead = 0

            while (totalRead < buffer.size) {
                val read = inputStream.read(buffer, totalRead, buffer.size - totalRead)
                if (read == -1) break
                totalRead += read
            }

            if (totalRead < 4) return false
            val chunkBytes = buffer.copyOf(totalRead)
            val headStr = String(chunkBytes.take(64).toByteArray()).lowercase()

            !headStr.contains("<html") && !headStr.contains("<!doctype") && !headStr.contains("{\"") && !headStr.contains("<?xml")
        } catch (_: Exception) {
            false
        } finally {
            chunkResp?.close()
        }
    }
}