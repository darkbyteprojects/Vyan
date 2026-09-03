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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

object LocalStreamProxy {
    @Volatile
    private var server: ApplicationEngine? = null

    private val client = NetworkClient.defaultClient

    const val PORT = 8080

    @Synchronized
    fun start() {
        if (server != null) return
        try {
            server = embeddedServer(CIO, port = PORT, host = "127.0.0.1") {
                routing {
                    get("/proxy/{lane}/{b64Url}/{b64Headers}/{path...}") { handleProxy(call) }
                    post("/proxy/{lane}/{b64Url}/{b64Headers}/{path...}") { handleProxy(call) }
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

    private suspend fun handleProxy(call: ApplicationCall) {
        val lane = call.parameters["lane"] ?: "UNI"
        val b64Url = call.parameters["b64Url"] ?: return call.respond(HttpStatusCode.BadRequest, "Missing URL")
        val b64Headers = call.parameters["b64Headers"] ?: "EMPTY"
        val pathSegments = call.parameters.getAll("path") ?: emptyList()

        val targetBaseUrl = try {
            String(Base64.decode(b64Url, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        } catch (_: Exception) { return call.respond(HttpStatusCode.BadRequest, "Invalid URL encoding") }

        val headersMap = decodeHeaders(b64Headers)

        if (pathSegments.isNotEmpty() && pathSegments.last() == "license") {
            handleDrmRequest(call, targetBaseUrl, headersMap, lane)
            return
        }

        val isDirectPassthrough = pathSegments.size == 1 && pathSegments[0] in setOf(
            "manifest.mpd", "playlist.m3u8", "seg.ts", "segment", "video.mp4", "video.ts", "key.bin", "track.m3u8", "video.mkv"
        )

        val targetUrl = if (isDirectPassthrough) {
            targetBaseUrl
        } else {
            try {
                val joinedPath = pathSegments.joinToString("/")
                val queryStr = call.request.queryParameters.entries().joinToString("&") { "${it.key}=${it.value.first()}" }
                val fullPath = if (queryStr.isNotBlank()) "$joinedPath?$queryStr" else joinedPath
                resolveWithQueryInheritance(targetBaseUrl, fullPath)
            } catch (e: Exception) { targetBaseUrl }
        }

        executeStreamRequest(call, targetUrl, headersMap, b64Headers, lane)
    }

    private suspend fun handleDrmRequest(call: ApplicationCall, targetKeyUrl: String, headers: Map<String, String>, lane: String) = withContext(Dispatchers.IO) {
        if (targetKeyUrl.contains(":") && !targetKeyUrl.startsWith("http", true)) {
            try {
                val parts = targetKeyUrl.split(":", limit = 2)
                if (parts.size == 2) {
                    val activeKid = parts[0].trim()
                    val activeK = parts[1].trim()
                    if (activeKid.length >= 16 && activeK.length >= 16) {
                        val jwk = JSONObject().apply {
                            put("keys", JSONArray().put(JSONObject().apply {
                                put("kty", "oct")
                                put("k", hexToBase64Url(activeK))
                                put("kid", hexToBase64Url(activeKid))
                            }))
                            put("type", "temporary") // FIX: Added type
                        }
                        call.respondText(jwk.toString(), ContentType.Application.Json)
                        return@withContext
                    }
                }
            } catch (_: Exception) {}
            call.respond(HttpStatusCode.BadRequest, "Invalid ClearKey HEX format")
            return@withContext
        }

        val requestBuilder = Request.Builder().url(targetKeyUrl)
        val ua = headers["User-Agent"] ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        requestBuilder.header("User-Agent", ua)
        requestBuilder.header("Accept", "*/*")

        headers.forEach { (k, v) ->
            val lk = k.lowercase()
            if (lk != "host" && lk != "content-length" && lk != "user-agent") {
                requestBuilder.header(k, v)
            }
        }

        if (call.request.httpMethod == HttpMethod.Post) {
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

            val raw = String(responseBytes, Charsets.UTF_8).trim()
            val jsonStartIndex = raw.indexOf("{").takeIf { it != -1 } ?: raw.indexOf("[")
            val sanitizedJson = if (jsonStartIndex != -1) raw.substring(jsonStartIndex).trim() else raw
            val convertedJwk = tryConvertFlatClearKeyToJwk(sanitizedJson)

            when {
                convertedJwk == null && sanitizedJson.contains("{") && sanitizedJson.contains("\"keys\"") -> call.respondText(sanitizedJson, ContentType.Application.Json)
                convertedJwk != null -> call.respondText(convertedJwk, ContentType.Application.Json)
                else -> call.respondBytes(responseBytes)
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, "DRM Proxy Error: ${e.message}")
        }
    }

    private fun tryConvertFlatClearKeyToJwk(raw: String): String? {
        if (!raw.contains("{")) return null
        return try {
            val obj = JSONObject(raw)
            if (obj.has("keys")) return null
            val keyIdHex = obj.optString("keyId", obj.optString("kid", "")).trim()
            val keyHex = obj.optString("key", obj.optString("k", "")).trim()
            if (keyIdHex.isBlank() || keyHex.isBlank() || keyIdHex.startsWith("http", true) || keyIdHex.length % 2 != 0) return null

            JSONObject().apply {
                put("keys", JSONArray().put(JSONObject().apply {
                    put("kty", "oct")
                    put("k", hexToBase64Url(keyHex))
                    put("kid", hexToBase64Url(keyIdHex))
                }))
                put("type", "temporary") // FIX: Added type
            }.toString()
        } catch (e: Exception) { null }
    }

    private fun hexToBase64Url(hex: String): String {
        val clean = hex.trim()
        val bytes = ByteArray(clean.length / 2)
        for (i in bytes.indices) bytes[i] = ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private suspend fun executeStreamRequest(call: ApplicationCall, targetUrl: String, headers: Map<String, String>, b64Headers: String, lane: String) = withContext(Dispatchers.IO) {
        val okHttpUrl = targetUrl.toHttpUrlOrNull() ?: try {
            URI(targetUrl).toASCIIString().toHttpUrlOrNull()
        } catch (e: Exception) { null }

        if (okHttpUrl == null) {
            call.respond(HttpStatusCode.BadRequest, "Malformed Proxy URL")
            return@withContext
        }

        val requestBuilder = Request.Builder().url(okHttpUrl)
        val ua = headers["User-Agent"] ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        requestBuilder.header("User-Agent", ua)
        requestBuilder.header("Accept", "*/*")
        requestBuilder.header("Connection", "keep-alive")

        val cookie = headers["Cookie"] ?: headers["cookie"]
        if (!cookie.isNullOrBlank()) requestBuilder.header("Cookie", cookie)

        val range = call.request.headers["Range"]
        if (!range.isNullOrBlank()) requestBuilder.header("Range", range)

        headers.forEach { (k, v) ->
            val lk = k.lowercase()
            if (lk != "user-agent" && lk != "accept" && lk != "connection" && lk != "host" && lk != "range" && lk != "cookie") {
                requestBuilder.header(k, v)
            }
        }

        try {
            val response = client.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                call.respond(HttpStatusCode.fromValue(response.code), "CDN Rejected Request (${response.code}) - ${response.message}")
                response.close()
                return@withContext
            }

            val effectiveUrl = response.request.url.toString()
            val contentType = response.header("Content-Type", "")?.lowercase() ?: ""

            if (targetUrl.contains(".m3u8", true) || contentType.contains("mpegurl")) {
                val body = response.body?.string() ?: ""
                response.close()
                call.respondText(rewriteM3u8(body, effectiveUrl, b64Headers, lane), ContentType.parse("application/vnd.apple.mpegurl"))
            } else if (targetUrl.contains(".mpd", true) || contentType.contains("dash+xml")) {
                val body = response.body?.string() ?: ""
                response.close()
                call.respondText(rewriteMpd(body, effectiveUrl, b64Headers, lane), ContentType.parse("application/dash+xml"))
            } else {
                val mime = if (contentType.isNotBlank()) contentType else if (targetUrl.contains(".ts", true)) "video/mp2t" else "video/mp4"

                response.header("Content-Range")?.let { call.response.headers.append(HttpHeaders.ContentRange, it) }
                response.header("Accept-Ranges")?.let { call.response.headers.append(HttpHeaders.AcceptRanges, it) }
                response.header("Content-Length")?.let { call.response.headers.append(HttpHeaders.ContentLength, it) }

                call.respondOutputStream(ContentType.parse(mime), HttpStatusCode.fromValue(response.code)) {
                    response.body?.byteStream()?.use { it.copyTo(this) }
                    response.close()
                }
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, "Proxy Transport Error: ${e.message}")
        }
    }

    private fun rewriteM3u8(manifest: String, baseUrl: String, b64Headers: String, lane: String): String {
        return manifest.lines().joinToString("\n") { line ->
            val trim = line.trim()
            when {
                trim.isEmpty() -> ""
                trim.startsWith("#") -> {
                    if (trim.contains("URI=\"")) {
                        trim.replace(Regex("""URI="([^"]+)"""")) { match ->
                            val originalUri = match.groupValues[1]
                            val abs = resolveWithQueryInheritance(baseUrl, originalUri)
                            val b64 = Base64.encodeToString(abs.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                            val ext = when {
                                abs.lowercase().contains(".m3u8") -> "track.m3u8"
                                abs.lowercase().contains(".key") || trim.contains("EXT-X-KEY") -> "key.bin"
                                else -> "seg.ts"
                            }
                            "URI=\"http://127.0.0.1:$PORT/proxy/$lane/$b64/$b64Headers/$ext\""
                        }
                    } else line
                }
                else -> {
                    val abs = resolveWithQueryInheritance(baseUrl, trim)
                    val b64 = Base64.encodeToString(abs.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                    val ext = if (abs.lowercase().contains(".m3u8")) "playlist.m3u8" else "seg.ts"
                    "http://127.0.0.1:$PORT/proxy/$lane/$b64/$b64Headers/$ext"
                }
            }
        }
    }

    private fun rewriteMpd(manifest: String, baseUrl: String, b64Headers: String, lane: String): String {
        var updated = manifest
        val regex = Regex("""<BaseURL>(http[s]?://[^<]+)</BaseURL>""")
        updated = updated.replace(regex) { match ->
            val abs = resolveWithQueryInheritance(baseUrl, match.groupValues[1])
            val b64 = Base64.encodeToString(abs.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            "<BaseURL>http://127.0.0.1:$PORT/proxy/$lane/$b64/$b64Headers/</BaseURL>"
        }

        if (!updated.contains("<BaseURL>")) {
            val b64Base = Base64.encodeToString(baseUrl.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val proxyBase = "http://127.0.0.1:$PORT/proxy/$lane/$b64Base/$b64Headers/"
            updated = updated.replaceFirst(Regex("""<Period([^>]*)>"""), "<Period$1>\n<BaseURL>$proxyBase</BaseURL>")
        }
        return updated
    }

    private fun resolveWithQueryInheritance(baseUrl: String, relativeUrl: String): String {
        return try {
            val baseUri = URI(baseUrl)
            val baseQuery = baseUri.query

            if (relativeUrl.startsWith("http://", true) || relativeUrl.startsWith("https://", true)) {
                val relUri = URI(relativeUrl)
                if (relUri.query.isNullOrBlank() && !baseQuery.isNullOrBlank()) return "$relativeUrl?$baseQuery"
                return relativeUrl
            }

            val resolved = baseUri.resolve(relativeUrl.substringBefore("?"))
            val relQuery = if (relativeUrl.contains("?")) relativeUrl.substringAfter("?") else null
            val finalQuery = when {
                !relQuery.isNullOrBlank() -> relQuery
                !baseQuery.isNullOrBlank() -> baseQuery
                else -> null
            }
            if (finalQuery != null) "${resolved.toString().substringBefore("?")}?$finalQuery" else resolved.toString()
        } catch (e: Exception) { relativeUrl }
    }

    private fun decodeHeaders(encoded: String): Map<String, String> {
        return try {
            if (encoded != "EMPTY" && encoded.isNotBlank()) {
                val json = String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
                val obj = JSONObject(json)
                val map = mutableMapOf<String, String>()
                obj.keys().forEach { map[it] = obj.getString(it) }
                map
            } else emptyMap()
        } catch (_: Exception) { emptyMap() }
    }

    fun createProxyUrl(url: String, headers: Map<String, String> = emptyMap(), lane: String = "UNI"): String {
        start()
        val b64Url = Base64.encodeToString(url.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val b64Headers = if (headers.isNotEmpty()) Base64.encodeToString(JSONObject(headers).toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) else "EMPTY"

        val lower = url.lowercase()
        val ext = when {
            lower.contains(".mpd") -> "manifest.mpd"
            lower.contains(".mp4") -> "video.mp4"
            lower.contains(".ts") -> "video.ts"
            lower.contains(".mkv") -> "video.mkv"
            else -> "playlist.m3u8"
        }
        return "http://127.0.0.1:$PORT/proxy/$lane/$b64Url/$b64Headers/$ext"
    }

    fun createProxyLicenseUrl(keyUrl: String, headers: Map<String, String> = emptyMap(), lane: String = "UNI"): String {
        start()
        val b64Url = Base64.encodeToString(keyUrl.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val b64Headers = if (headers.isNotEmpty()) Base64.encodeToString(JSONObject(headers).toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) else "EMPTY"
        return "http://127.0.0.1:$PORT/proxy/$lane/$b64Url/$b64Headers/license"
    }
}