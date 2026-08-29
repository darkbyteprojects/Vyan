package com.vyan.xtreamplayer.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object IptvAliveChecker {
    // Dedicated client so slow servers don't freeze the scanner
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    suspend fun isStreamAlive(url: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    // CRITICAL: Many IPTV servers block standard HTTP requests. We must pretend to be ExoPlayer.
                    .header("User-Agent", "ExoPlayer/2.18.1")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext false

                    val body = response.body ?: return@withContext false
                    val inputStream = body.byteStream()

                    val buffer = ByteArray(256)
                    var totalRead = 0

                    // Safely loop to guarantee we read enough bytes from the network buffer
                    while (totalRead < 256) {
                        val bytesRead = inputStream.read(buffer, totalRead, 256 - totalRead)
                        if (bytesRead == -1) break
                        totalRead += bytesRead
                    }

                    val bytes = if (totalRead > 0) buffer.copyOf(totalRead) else ByteArray(0)
                    if (bytes.isEmpty()) return@withContext false

                    // 1. Check for standard TS video packet (0x47)
                    if (bytes[0] == 0x47.toByte()) return@withContext true

                    // 2. Check for M3U8 playlist header
                    val headerString = String(bytes).lowercase()
                    if (headerString.contains("#extm3u") || headerString.contains("#extinf")) return@withContext true

                    // 3. Check for MP4 Box headers (ftyp)
                    if (bytes.size >= 8) {
                        val hex = bytes.take(16).joinToString("") { "%02X".format(it) }
                        if (hex.contains("66747970")) return@withContext true
                    }

                    // 4. Fallback: If it's a 200 OK and isn't an HTML error page, assume it's a raw video stream
                    if (headerString.contains("<!doctype html>") || headerString.contains("<html")) {
                        return@withContext false
                    }

                    return@withContext true
                }
            } catch (e: Exception) {
                return@withContext false
            }
        }
    }
}