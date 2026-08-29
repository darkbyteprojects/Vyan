package com.vyan.xtreamplayer.utils

import android.util.Base64
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

object LocalStreamProxy {
    @Volatile
    private var server: ApplicationEngine? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    const val PORT = 8080

    @Synchronized
    fun start() {
        if (server != null) return
        try {
            server = embeddedServer(CIO, port = PORT, host = "127.0.0.1") {
                routing {
                    get("/proxy/{filename...}") {
                        handleProxyCall(call)
                    }

                    get("/proxy") {
                        handleProxyCall(call)
                    }

                    get("/") {
                        call.respondRedirect("https://vyan.dbprojects.workers.dev/", permanent = false)
                    }
                }
            }.start(wait = false)
        } catch (_: Exception) {}
    }

    @Synchronized
    fun stop() {
        try {
            server?.stop(500, 1000)
            server = null
        } catch (_: Exception) {}
    }

    private suspend fun handleProxyCall(call: ApplicationCall) {
        val encodedUrl = call.request.queryParameters["url"]
        val encodedHeaders = call.request.queryParameters["h"] ?: ""

        if (encodedUrl.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, "Missing URL parameter")
            return
        }

        val targetUrl = try {
            String(Base64.decode(encodedUrl, Base64.URL_SAFE or Base64.NO_WRAP))
        } catch (_: Exception) {
            call.respond(HttpStatusCode.BadRequest, "Invalid URL encoding")
            return
        }

        val headersMap = try {
            if (encodedHeaders.isNotBlank()) {
                val jsonStr = String(Base64.decode(encodedHeaders, Base64.URL_SAFE or Base64.NO_WRAP))
                val jsonObj = JSONObject(jsonStr)
                val map = mutableMapOf<String, String>()
                jsonObj.keys().forEach { k -> map[k] = jsonObj.getString(k) }
                map
            } else emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }

        handleProxyRequest(call, targetUrl, headersMap)
    }

    fun createProxyUrl(originalUrl: String, headers: Map<String, String> = emptyMap()): String {
        start() // Ensure server is running

        val encodedUrl = Base64.encodeToString(originalUrl.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val encodedHeaders = if (headers.isNotEmpty()) {
            val json = JSONObject(headers).toString()
            Base64.encodeToString(json.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } else ""

        val lower = originalUrl.lowercase()
        val extensionPath = when {
            lower.contains(".m3u8") || lower.contains("/hls") || lower.contains("playlist") -> "/proxy/playlist.m3u8"
            lower.contains(".mpd") -> "/proxy/manifest.mpd"
            lower.contains(".ts") -> "/proxy/segment.ts"
            lower.contains(".m4s") || lower.contains(".mp4") -> "/proxy/segment.m4s"
            else -> "/proxy/stream.m3u8"
        }

        return if (encodedHeaders.isNotBlank()) {
            "http://127.0.0.1:$PORT$extensionPath?url=$encodedUrl&h=$encodedHeaders"
        } else {
            "http://127.0.0.1:$PORT$extensionPath?url=$encodedUrl"
        }
    }

    private suspend fun handleProxyRequest(call: ApplicationCall, targetUrl: String, headers: Map<String, String>) = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url(targetUrl)

        // Case-insensitive User-Agent extraction
        val userAgent = headers.entries.firstOrNull { it.key.equals("user-agent", ignoreCase = true) }?.value
            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:154.0) Gecko/20100101 Firefox/154.0"

        requestBuilder.header("User-Agent", userAgent)
        requestBuilder.header("Accept", "*/*")
        requestBuilder.header("Connection", "keep-alive")

        val rangeHeader = call.request.headers["Range"]
        if (!rangeHeader.isNullOrBlank()) {
            requestBuilder.header("Range", rangeHeader)
        }

        // Apply custom headers while filtering out pseudo/transport headers
        headers.forEach { (k, v) ->
            val lowerK = k.lowercase()
            if (lowerK != "user-agent" && lowerK != "accept" && lowerK != "connection" && lowerK != "host" && lowerK != "range") {
                requestBuilder.header(k, v)
            }
        }

        try {
            val response = client.newCall(requestBuilder.build()).execute()

            if (!response.isSuccessful) {
                call.respond(HttpStatusCode.fromValue(response.code), "CDN returned HTTP ${response.code}")
                response.close()
                return@withContext
            }

            val rawContentType = response.header("Content-Type", "")?.lowercase() ?: ""

            if (rawContentType.contains("text/html") || rawContentType.contains("application/json")) {
                call.respond(HttpStatusCode.Forbidden, "CDN blocked request with challenge/error page.")
                response.close()
                return@withContext
            }

            if (targetUrl.contains(".m3u8", ignoreCase = true) || rawContentType.contains("mpegurl") || rawContentType.contains("application/x-mpegurl")) {
                val bodyString = response.body?.string() ?: ""
                response.close()

                if (bodyString.contains("<html", ignoreCase = true) || !bodyString.startsWith("#EXTM3U")) {
                    call.respond(HttpStatusCode.Forbidden, "Invalid M3U8 content returned.")
                    return@withContext
                }

                val rewrittenManifest = rewriteManifest(bodyString, targetUrl, headers)
                call.respondText(rewrittenManifest, ContentType.parse("application/vnd.apple.mpegurl"))
            } else {
                val resolvedMime = when {
                    rawContentType.isNotBlank() -> rawContentType
                    targetUrl.contains(".ts", ignoreCase = true) -> "video/mp2t"
                    targetUrl.contains(".m4s", ignoreCase = true) || targetUrl.contains(".mp4", ignoreCase = true) -> "video/mp4"
                    else -> "application/octet-stream"
                }

                val responseStatus = HttpStatusCode.fromValue(response.code)
                call.respondOutputStream(ContentType.parse(resolvedMime), responseStatus) {
                    response.body?.byteStream()?.use { input ->
                        input.copyTo(this)
                    }
                    response.close()
                }
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, e.message ?: "Proxy transport error")
        }
    }

    private fun rewriteManifest(manifest: String, baseUrl: String, headers: Map<String, String>): String {
        val lines = manifest.lines()
        val sb = StringBuilder()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("#EXT-X-KEY:", ignoreCase = true) || trimmed.startsWith("#EXT-X-MAP:", ignoreCase = true)) {
                val uriRegex = Regex("""URI="([^"]+)"""")
                val match = uriRegex.find(trimmed)
                if (match != null) {
                    val originalUri = match.groupValues[1]
                    if (!originalUri.startsWith("data:", ignoreCase = true)) {
                        val absoluteUri = resolveUrlPreservingQuery(baseUrl, originalUri)
                        val proxyUri = createProxyUrl(absoluteUri, headers)
                        val newLine = trimmed.replace("""URI="$originalUri"""", """URI="$proxyUri"""")
                        sb.append(newLine).append("\n")
                        continue
                    }
                }
                sb.append(line).append("\n")
            } else if (!trimmed.startsWith("#")) {
                val absoluteUrl = resolveUrlPreservingQuery(baseUrl, trimmed)
                val proxyUrl = createProxyUrl(absoluteUrl, headers)
                sb.append(proxyUrl).append("\n")
            } else {
                sb.append(line).append("\n")
            }
        }
        return sb.toString()
    }

    private fun resolveUrlPreservingQuery(baseUrl: String, relativeUrl: String): String {
        return try {
            val baseUri = URI(baseUrl)
            val baseQuery = baseUri.query

            if (relativeUrl.startsWith("http://", ignoreCase = true) || relativeUrl.startsWith("https://", ignoreCase = true)) {
                if (!relativeUrl.contains("?") && !baseQuery.isNullOrBlank()) {
                    return "$relativeUrl?$baseQuery"
                }
                return relativeUrl
            }

            val resolvedUri = baseUri.resolve(relativeUrl.substringBefore("?"))
            val relQuery = if (relativeUrl.contains("?")) relativeUrl.substringAfter("?") else null

            val finalQuery = when {
                !relQuery.isNullOrBlank() -> relQuery
                !baseQuery.isNullOrBlank() -> baseQuery
                else -> null
            }

            if (finalQuery != null) "${resolvedUri.toString().substringBefore("?")}?$finalQuery" else resolvedUri.toString()
        } catch (_: Exception) {
            relativeUrl
        }
    }
}