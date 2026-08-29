package com.vyan.xtreamplayer.utils

import android.util.Base64
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
                    // Handles GET & POST for streams, segments, and DRM keys
                    get("/p/{b64Url}/{b64Headers}/{path...}") {
                        handlePathBasedProxy(call)
                    }
                    post("/p/{b64Url}/{b64Headers}/{path...}") {
                        handlePathBasedProxy(call)
                    }

                    get("/proxy/{filename...}") { handleLegacyProxyCall(call) }
                    get("/proxy") { handleLegacyProxyCall(call) }
                    get("/") { call.respondRedirect("https://vyan.dbprojects.workers.dev/", permanent = false) }
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

    private suspend fun handlePathBasedProxy(call: ApplicationCall) {
        val b64Url = call.parameters["b64Url"] ?: return call.respond(HttpStatusCode.BadRequest, "Missing URL")
        val b64Headers = call.parameters["b64Headers"] ?: "EMPTY"
        val pathSegments = call.parameters.getAll("path") ?: emptyList()

        val targetBaseUrl = try {
            String(Base64.decode(b64Url, Base64.URL_SAFE or Base64.NO_WRAP))
        } catch (_: Exception) { return call.respond(HttpStatusCode.BadRequest, "Invalid URL encoding") }

        val headersMap = decodeHeaders(b64Headers)

        // 1. Universal DRM License / Key Proxy Handler
        if (pathSegments.isNotEmpty() && pathSegments.last() == "clearkey_license") {
            handleUniversalDrmProxy(call, targetBaseUrl, headersMap)
            return
        }

        // 2. Stream & Segment Handler
        val isRootRequest = pathSegments.size == 1 && (pathSegments[0] == "manifest.mpd" || pathSegments[0] == "playlist.m3u8" || pathSegments[0] == "stream.m3u8")

        val targetUrl = if (isRootRequest) {
            targetBaseUrl
        } else {
            try {
                val joinedPath = pathSegments.joinToString("/")
                val queryParts = mutableListOf<String>()
                call.request.queryParameters.forEach { key, values ->
                    values.forEach { value -> queryParts.add("$key=$value") }
                }
                val queryStr = queryParts.joinToString("&")
                val pathWithQuery = if (queryStr.isNotBlank()) "$joinedPath?$queryStr" else joinedPath
                resolveUrlPreservingQuery(targetBaseUrl, pathWithQuery)
            } catch (e: Exception) {
                targetBaseUrl
            }
        }

        handleProxyRequest(call, targetUrl, headersMap, b64Headers)
    }

    private suspend fun handleUniversalDrmProxy(call: ApplicationCall, targetKeyUrl: String, headers: Map<String, String>) = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url(targetKeyUrl)

        val userAgent = headers.entries.firstOrNull { it.key.equals("user-agent", ignoreCase = true) }?.value
            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:154.0) Gecko/20100101 Firefox/154.0"

        requestBuilder.header("User-Agent", userAgent)
        requestBuilder.header("Accept", "*/*")
        headers.forEach { (k, v) ->
            if (!k.equals("user-agent", ignoreCase = true) && !k.equals("host", ignoreCase = true) && !k.equals("content-length", ignoreCase = true)) {
                requestBuilder.header(k, v)
            }
        }

        // SMART DRM ROUTING: Forward POST challenge if it's a standard DRM server, fallback to GET for custom PHP fetchers
        val isPhpFetcher = targetKeyUrl.contains(".php", ignoreCase = true) || targetKeyUrl.contains("keyid=", ignoreCase = true)

        if (call.request.httpMethod == HttpMethod.Post && !isPhpFetcher) {
            val bodyBytes = call.receive<ByteArray>()
            val cType = call.request.headers["Content-Type"] ?: "application/octet-stream"
            requestBuilder.post(bodyBytes.toRequestBody(cType.toMediaTypeOrNull()))
        } else {
            requestBuilder.get()
        }

        try {
            val response = client.newCall(requestBuilder.build()).execute()
            val responseBytes = response.body?.bytes() ?: ByteArray(0)
            response.close()

            val rawString = String(responseBytes).trim()

            // Handle pure JSON, Custom Hex Pairs, or Raw Binary (Widevine)
            if (rawString.contains("{") && rawString.contains("keys")) {
                call.respondText(rawString, ContentType.Application.Json)
            } else if (rawString.length in 32..100 && rawString.contains(":")) {
                val keyId = rawString.substringBefore(":")
                val key = rawString.substringAfter(":")
                val jwk = buildClearKeyJwk(keyId, key)
                if (jwk != null) {
                    call.respondText(jwk, ContentType.Application.Json)
                } else {
                    call.respondBytes(responseBytes)
                }
            } else {
                call.respondBytes(responseBytes)
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, "Failed to proxy DRM key: ${e.message}")
        }
    }

    private fun buildClearKeyJwk(keyIdHex: String, keyHex: String): String? {
        fun hexToBase64Url(hex: String): String? {
            val clean = hex.replace("-", "").trim()
            if (clean.length % 2 != 0 || clean.length < 16) return null
            val bytes = clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }
        return try {
            val kId = hexToBase64Url(keyIdHex) ?: return null
            val k = hexToBase64Url(keyHex) ?: return null
            """{"keys":[{"kty":"oct","k":"$k","kid":"$kId"}],"type":"temporary"}"""
        } catch (e: Exception) { null }
    }

    private suspend fun handleLegacyProxyCall(call: ApplicationCall) {
        val encodedUrl = call.request.queryParameters["url"]
        val encodedHeaders = call.request.queryParameters["h"] ?: "EMPTY"

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

        val headersMap = decodeHeaders(encodedHeaders)
        handleProxyRequest(call, targetUrl, headersMap, encodedHeaders)
    }

    private fun decodeHeaders(encodedHeaders: String): Map<String, String> {
        return try {
            if (encodedHeaders != "EMPTY" && encodedHeaders.isNotBlank()) {
                val jsonStr = String(Base64.decode(encodedHeaders, Base64.URL_SAFE or Base64.NO_WRAP))
                val jsonObj = JSONObject(jsonStr)
                val map = mutableMapOf<String, String>()
                jsonObj.keys().forEach { k -> map[k] = jsonObj.getString(k) }
                map
            } else emptyMap()
        } catch (_: Exception) { emptyMap() }
    }

    fun createProxyUrl(originalUrl: String, headers: Map<String, String> = emptyMap()): String {
        start()

        val encodedUrl = Base64.encodeToString(originalUrl.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val encodedHeaders = if (headers.isNotEmpty()) {
            val json = JSONObject(headers).toString()
            Base64.encodeToString(json.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } else "EMPTY"

        val lower = originalUrl.lowercase()
        val extensionPath = when {
            lower.contains(".mpd") -> "manifest.mpd"
            lower.contains(".m3u8") || lower.contains("/hls") || lower.contains("playlist") -> "playlist.m3u8"
            else -> "stream.m3u8"
        }

        return "http://127.0.0.1:$PORT/p/$encodedUrl/$encodedHeaders/$extensionPath"
    }

    fun createProxyLicenseUrl(originalKeyUrl: String, headers: Map<String, String> = emptyMap()): String {
        start()

        val encodedUrl = Base64.encodeToString(originalKeyUrl.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val encodedHeaders = if (headers.isNotEmpty()) {
            val json = JSONObject(headers).toString()
            Base64.encodeToString(json.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } else "EMPTY"

        return "http://127.0.0.1:$PORT/p/$encodedUrl/$encodedHeaders/clearkey_license"
    }

    private suspend fun handleProxyRequest(call: ApplicationCall, targetUrl: String, headers: Map<String, String>, b64Headers: String) = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url(targetUrl)

        val userAgent = headers.entries.firstOrNull { it.key.equals("user-agent", ignoreCase = true) }?.value
            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:154.0) Gecko/20100101 Firefox/154.0"

        requestBuilder.header("User-Agent", userAgent)
        requestBuilder.header("Accept", "*/*")
        requestBuilder.header("Connection", "keep-alive")

        val rangeHeader = call.request.headers["Range"]
        if (!rangeHeader.isNullOrBlank()) {
            requestBuilder.header("Range", rangeHeader)
        }

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

                val rewrittenManifest = rewriteM3u8(bodyString, targetUrl, b64Headers)
                call.respondText(rewrittenManifest, ContentType.parse("application/vnd.apple.mpegurl"))
            } else if (targetUrl.contains(".mpd", ignoreCase = true) || rawContentType.contains("dash+xml")) {
                val bodyString = response.body?.string() ?: ""
                response.close()

                if (bodyString.contains("<html", ignoreCase = true) || !bodyString.contains("<MPD", ignoreCase = true)) {
                    call.respond(HttpStatusCode.Forbidden, "Invalid MPD content returned.")
                    return@withContext
                }

                val rewrittenManifest = rewriteMpd(bodyString, b64Headers)
                call.respondText(rewrittenManifest, ContentType.parse("application/dash+xml"))
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

    private fun rewriteM3u8(manifest: String, baseUrl: String, b64Headers: String): String {
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
                        val newB64Url = Base64.encodeToString(absoluteUri.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                        val proxyUri = "http://127.0.0.1:$PORT/p/$newB64Url/$b64Headers/key_or_map"
                        val newLine = trimmed.replace("""URI="$originalUri"""", """URI="$proxyUri"""")
                        sb.append(newLine).append("\n")
                        continue
                    }
                }
                sb.append(line).append("\n")
            } else if (!trimmed.startsWith("#")) {
                val absoluteUrl = resolveUrlPreservingQuery(baseUrl, trimmed)
                val newB64Url = Base64.encodeToString(absoluteUrl.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                val proxyUrl = "http://127.0.0.1:$PORT/p/$newB64Url/$b64Headers/segment.ts"
                sb.append(proxyUrl).append("\n")
            } else {
                sb.append(line).append("\n")
            }
        }
        return sb.toString()
    }

    private fun rewriteMpd(manifest: String, b64Headers: String): String {
        val baseUrlRegex = Regex("""<BaseURL>(http[s]?://[^<]+)</BaseURL>""")
        return manifest.replace(baseUrlRegex) { matchResult ->
            val absoluteUrl = matchResult.groupValues[1]
            val newB64Url = Base64.encodeToString(absoluteUrl.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val proxyUrl = "http://127.0.0.1:$PORT/p/$newB64Url/$b64Headers/"
            "<BaseURL>$proxyUrl</BaseURL>"
        }
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