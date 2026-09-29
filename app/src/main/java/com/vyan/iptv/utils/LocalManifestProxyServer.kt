package com.vyan.iptv.utils

import android.util.Base64
import com.vyan.iptv.stream.PlaybackMethodRouter
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

object LocalStreamProxy {

    // Removed 'const' so the port can be assigned dynamically
    private var PORT = 8080

    private var server: ApplicationEngine? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val request = chain.request()
            android.util.Log.e("PROXY_OUTBOUND", "Executing Request to: ${request.url}")
            android.util.Log.e("PROXY_OUTBOUND", "Outbound Headers: \n${request.headers}")
            chain.proceed(request)
        }
        .build()

    @Synchronized
    fun start() {
        if (server != null) return

        // Dynamically find a free port to prevent BindException when original app is running
        try {
            ServerSocket(0).use { socket ->
                PORT = socket.localPort
            }
        } catch (e: Exception) {
            PORT = (8001..9000).random()
        }

        server = embeddedServer(CIO, port = PORT, host = "127.0.0.1") {
            routing {
                get("/proxy/{lane}/{b64Url}/{b64Headers}/{path...}") {
                    handleProxyRequest(call, isLicense = false)
                }

                route("/license/{lane}/{b64Url}/{b64Headers}/{path...}") {
                    handle {
                        handleProxyRequest(call, isLicense = true)
                    }
                }
            }
        }.start(wait = false)
    }

    private suspend fun handleProxyRequest(call: ApplicationCall, isLicense: Boolean) {
        val b64Url = call.parameters["b64Url"] ?: return call.respond(HttpStatusCode.BadRequest)
        val b64Headers = call.parameters["b64Headers"] ?: ""
        val lane = call.parameters["lane"] ?: "UNI"
        val pathSegment = call.parameters.getAll("path")?.joinToString("/") ?: ""

        val baseTargetUrl = try {
            String(Base64.decode(b64Url, Base64.URL_SAFE or Base64.NO_WRAP)).trim().trimEnd('?', '&')
        } catch (_: Exception) {
            return call.respond(HttpStatusCode.BadRequest, "Invalid Target URL")
        }

        val finalUpstreamUrl = if (pathSegment.isEmpty() || pathSegment == "manifest.mpd" || pathSegment == "playlist.m3u8" || pathSegment == "chunk.m4s" || pathSegment == "key") {
            baseTargetUrl
        } else {
            resolveWithQueryInheritance(baseTargetUrl, pathSegment)
        }

        val headersMap = mutableMapOf<String, String>()

        if (b64Headers.isNotBlank()) {
            try {
                val jsonStr = String(Base64.decode(b64Headers, Base64.URL_SAFE or Base64.NO_WRAP))
                val json = JSONObject(jsonStr)
                json.keys().forEach { key ->
                    headersMap[key] = json.getString(key)
                }
            } catch (_: Exception) {}
        }

        call.request.headers.forEach { key, values ->
            val kLower = key.lowercase()
            if (kLower != "host" && kLower != "accept-encoding") {
                headersMap.putIfAbsent(key, values.joinToString("; "))
            }
        }

        headersMap.remove("Host")
        headersMap.keys.removeIf { it.equals("Accept-Encoding", ignoreCase = true) }
        headersMap.keys.removeIf { it.equals("xxx", ignoreCase = true) }

        val bodyBytes = if (isLicense && call.request.httpMethod == HttpMethod.Post) {
            try { call.receive<ByteArray>() } catch (_: Exception) { null }
        } else null

        try {
            val reqBuilder = Request.Builder().url(finalUpstreamUrl)
            headersMap.forEach { (k, v) ->
                if (v.isNotBlank()) reqBuilder.header(k, v)
            }

            if (!headersMap.keys.any { it.equals("Cookie", ignoreCase = true) } && finalUpstreamUrl.contains("__hdnea__")) {
                val queryParams = finalUpstreamUrl.substringAfter("?", "")
                if (queryParams.contains("__hdnea__")) {
                    val token = queryParams.substringAfter("__hdnea__=").substringBefore("&")
                    reqBuilder.header("Cookie", "__hdnea__=$token")
                }
            }

            if (bodyBytes != null) {
                reqBuilder.post(bodyBytes.toRequestBody())
            }

            var response = httpClient.newCall(reqBuilder.build()).execute()

            val isJioCdn = finalUpstreamUrl.contains(".jio.com", ignoreCase = true) || finalUpstreamUrl.contains("jiotv", ignoreCase = true)
            if (!response.isSuccessful && (response.code == 403 || response.code == 401) && isJioCdn) {
                val fallbackUas = listOf(PlaybackMethodRouter.JIO_STB_UA, PlaybackMethodRouter.JIO_MOBILE_UA)

                for (fallbackUa in fallbackUas) {
                    val currentUa = headersMap.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value ?: ""
                    if (currentUa.equals(fallbackUa, ignoreCase = true)) continue

                    response.close()

                    val retryBuilder = Request.Builder().url(finalUpstreamUrl)
                    headersMap.forEach { (k, v) ->
                        if (v.isNotBlank() &&
                            !k.equals("User-Agent", ignoreCase = true) &&
                            !k.equals("Origin", ignoreCase = true) &&
                            !k.equals("Referer", ignoreCase = true)) {
                            retryBuilder.header(k, v)
                        }
                    }
                    retryBuilder.header("User-Agent", fallbackUa)

                    if (!headersMap.keys.any { it.equals("Cookie", ignoreCase = true) } && finalUpstreamUrl.contains("__hdnea__")) {
                        val queryParams = finalUpstreamUrl.substringAfter("?", "")
                        if (queryParams.contains("__hdnea__")) {
                            val token = queryParams.substringAfter("__hdnea__=").substringBefore("&")
                            retryBuilder.header("Cookie", "__hdnea__=$token")
                        }
                    }

                    if (bodyBytes != null) {
                        retryBuilder.post(bodyBytes.toRequestBody())
                    }

                    response = httpClient.newCall(retryBuilder.build()).execute()
                    if (response.isSuccessful) break
                }
            }

            val responseBody = response.body?.bytes() ?: ByteArray(0)
            val contentType = response.header("Content-Type") ?: "application/octet-stream"
            val statusCode = HttpStatusCode.fromValue(response.code)

            if (response.isSuccessful && finalUpstreamUrl.contains(".mpd", ignoreCase = true) && !isLicense) {
                val rawManifest = String(responseBody, Charsets.UTF_8)
                val rewritten = rewriteMpd(rawManifest, finalUpstreamUrl, b64Headers, lane)
                call.respondBytes(rewritten.toByteArray(Charsets.UTF_8), ContentType.parse(contentType), HttpStatusCode.OK)
            } else {
                call.respondBytes(responseBody, ContentType.parse(contentType), statusCode)
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadGateway, e.message ?: "Proxy Request Failed")
        }
    }

    private fun rewriteMpd(manifest: String, baseUrl: String, b64Headers: String, lane: String): String {
        var updated = manifest

        val baseUrlRegex = Regex("""<BaseURL>\s*(http[s]?://[^<\s][^<]*?)\s*</BaseURL>""")
        updated = updated.replace(baseUrlRegex) { match ->
            val abs = resolveWithQueryInheritance(baseUrl, match.groupValues[1])
            val b64 = Base64.encodeToString(abs.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            "<BaseURL>http://127.0.0.1:$PORT/proxy/$lane/$b64/$b64Headers/</BaseURL>"
        }

        val mediaAttrRegex = Regex("""(media|initialization|sourceURL)="([^"]+)"""")
        updated = updated.replace(mediaAttrRegex) { match ->
            val attrName = match.groupValues[1]
            val attrValue = match.groupValues[2]

            if (attrValue.startsWith("http", ignoreCase = true)) {
                val abs = resolveWithQueryInheritance(baseUrl, attrValue)
                val b64 = Base64.encodeToString(abs.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                """$attrName="http://127.0.0.1:$PORT/proxy/$lane/$b64/$b64Headers/chunk.m4s""""
            } else {
                match.value
            }
        }

        if (!updated.contains("http://127.0.0.1:$PORT/proxy/")) {
            val b64Base = Base64.encodeToString(baseUrl.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val proxyBase = "http://127.0.0.1:$PORT/proxy/$lane/$b64Base/$b64Headers/"

            val selfClosingPeriod = Regex("""<Period([^>]*)/>""")
            val openPeriod = Regex("""<Period([^>]*)>""")

            val selfClosingMatch = selfClosingPeriod.find(updated)
            updated = if (selfClosingMatch != null) {
                val replacement = "<Period${selfClosingMatch.groupValues[1]}>\n<BaseURL>$proxyBase</BaseURL>\n</Period>"
                updated.replaceRange(selfClosingMatch.range, replacement)
            } else {
                val openMatch = openPeriod.find(updated)
                if (openMatch != null) {
                    val replacement = "<Period${openMatch.groupValues[1]}>\n<BaseURL>$proxyBase</BaseURL>"
                    updated.replaceRange(openMatch.range, replacement)
                } else {
                    updated
                }
            }
        }

        return updated
    }

    private fun resolveWithQueryInheritance(baseUrl: String, relativeUrl: String): String {
        return try {
            val cleanBase = baseUrl.trimEnd('?', '&')
            val baseQuery = if (cleanBase.contains("?")) cleanBase.substringAfter("?") else ""

            val cleanRel = relativeUrl.trimEnd('?', '&')
            val relBase = cleanRel.substringBefore("?")
            val relQuery = if (cleanRel.contains("?")) cleanRel.substringAfter("?") else ""

            val queryParts = mutableListOf<String>()
            if (baseQuery.isNotBlank()) queryParts.add(baseQuery)
            if (relQuery.isNotBlank()) queryParts.add(relQuery)

            val mergedQuery = if (queryParts.isNotEmpty()) "?${queryParts.joinToString("&")}" else ""

            if (cleanRel.startsWith("http://", true) || cleanRel.startsWith("https://", true)) {
                "$relBase$mergedQuery"
            } else {
                val resolvedUri = URI(cleanBase).resolve(relBase).toString().substringBefore("?")
                "$resolvedUri$mergedQuery"
            }
        } catch (_: Exception) {
            relativeUrl
        }
    }

    fun createProxyUrl(targetUrl: String, headers: Map<String, String>, lane: String): String {
        val cleanUrl = targetUrl.trim().trimEnd('?', '&')
        val b64Url = Base64.encodeToString(cleanUrl.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val jsonHeaders = JSONObject(headers).toString()
        val b64Headers = Base64.encodeToString(jsonHeaders.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return "http://127.0.0.1:$PORT/proxy/$lane/$b64Url/$b64Headers/manifest.mpd"
    }

    fun createProxyLicenseUrl(targetUrl: String, headers: Map<String, String>, lane: String): String {
        val cleanUrl = targetUrl.trim().trimEnd('?', '&')
        val b64Url = Base64.encodeToString(cleanUrl.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val jsonHeaders = JSONObject(headers).toString()
        val b64Headers = Base64.encodeToString(jsonHeaders.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return "http://127.0.0.1:$PORT/license/$lane/$b64Url/$b64Headers/key"
    }
}