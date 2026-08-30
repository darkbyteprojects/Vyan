package com.vyan.xtreamplayer.utils

import android.net.Uri
import android.util.Base64
import androidx.media3.common.C
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class NormalizedStream(
    val proxyStreamUrl: String,
    val mimeType: String,
    val headers: Map<String, String>,
    val drmScheme: UUID?,
    val proxyDrmLicenseUrl: String?,
    val drmHeaders: Map<String, String>?,
    val localJwk: String?
)

enum class InternalFormatLane { F1, F2, F3, F4, F5, F6, GEN }

object StreamFormatNormalizer {

    private const val JIO_STB_UA = "JioTV.Plus/2.8.4_2076/StreamFlex(StreamFlex;JioSTB) JioTvPlus-AndroidTv"
    private const val JIO_MOBILE_UA = "plaYtv/7.1.3 (Linux;Android 13) - @Vortex Tv - ExoPlayerLib/824.0"
    private const val GENERIC_BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    fun normalize(
        url: String, keyId: String, key: String, cookie: String, userAgent: String, baseHeaders: Map<String, String>
    ): NormalizedStream {
        val format = detectFormat(url, keyId, key, cookie)
        return when (format) {
            InternalFormatLane.F1 -> normalizeFormat1(url, keyId, cookie, userAgent, baseHeaders)
            InternalFormatLane.F2 -> normalizeFormat2(url, keyId, cookie, userAgent, baseHeaders)
            InternalFormatLane.F3 -> normalizeFormat3(url, keyId, key, cookie, userAgent, baseHeaders)
            InternalFormatLane.F4 -> normalizeFormat4(url, keyId, key, cookie, userAgent, baseHeaders)
            InternalFormatLane.F5 -> normalizeFormat5(url, keyId, cookie, userAgent, baseHeaders)
            InternalFormatLane.F6 -> normalizeFormat6(url, keyId, cookie, userAgent, baseHeaders)
            InternalFormatLane.GEN -> normalizeGeneric(url, keyId, key, cookie, userAgent, baseHeaders)
        }
    }

    private fun detectFormat(url: String, keyId: String, key: String, cookie: String): InternalFormatLane {
        return when {
            url.contains("|Cookie=", true) || url.contains("|cookie=", true) || url.contains("&xxx=") || keyId.contains("streamflexsmm.in", true) -> InternalFormatLane.F1
            keyId.contains(".php", true) && url.contains("jio", true) -> InternalFormatLane.F6
            // FIX: Check url as well as keyId for allinonereborn2.online
            keyId.contains(".php", true) || keyId.contains("keyid=", true) || keyId.contains("allinonereborn2.online", true) || url.contains("allinonereborn2.online", true) -> InternalFormatLane.F2
            (keyId.contains(":") || (keyId.isNotBlank() && key.isNotBlank())) && url.contains("jio", true) -> InternalFormatLane.F4
            (keyId.contains(":") || (keyId.isNotBlank() && key.isNotBlank())) && !url.contains("jio", true) -> InternalFormatLane.F5
            cookie.contains("__hdnea__") && !url.contains("__hdnea__") -> InternalFormatLane.F3
            else -> InternalFormatLane.GEN
        }
    }

    private fun mimeTypeFor(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains(".mpd") -> androidx.media3.common.MimeTypes.APPLICATION_MPD
            lower.contains(".m3u8") -> androidx.media3.common.MimeTypes.APPLICATION_M3U8
            lower.contains(".mp4") -> androidx.media3.common.MimeTypes.VIDEO_MP4
            lower.contains(".ts") -> androidx.media3.common.MimeTypes.VIDEO_MP2T
            else -> androidx.media3.common.MimeTypes.APPLICATION_M3U8
        }
    }

    private fun sanitizeUa(ua: String, url: String): String {
        val lowerUa = ua.lowercase()
        val lowerUrl = url.lowercase()
        if (lowerUrl.contains("allinonereborn")) return GENERIC_BROWSER_UA
        val isJio = lowerUrl.contains("jio")
        if (isJio && (lowerUa.contains("ott navigator") || lowerUa.startsWith("@") || ua.isBlank())) {
            return if (lowerUrl.contains("_mob")) JIO_MOBILE_UA else JIO_STB_UA
        }
        if (!isJio && (lowerUa.contains("ott navigator") || lowerUa.startsWith("@") || ua.isBlank())) return GENERIC_BROWSER_UA
        return ua.ifBlank { GENERIC_BROWSER_UA }
    }

    private fun applyDomainSpoofing(url: String, headers: MutableMap<String, String>) {
        val lowerUrl = url.lowercase()
        if (lowerUrl.contains("allinonereborn")) {
            headers["Origin"] = "https://allinonereborn2.online"
            headers["Referer"] = "https://allinonereborn2.online/"
        } else if (lowerUrl.contains("jio")) {
            headers.putIfAbsent("Origin", "https://www.jiotv.com")
            headers.putIfAbsent("Referer", "https://www.jiotv.com/")
        } else if (lowerUrl.contains("sonyliv")) {
            headers.putIfAbsent("Origin", "https://www.sonyliv.com")
            headers.putIfAbsent("Referer", "https://www.sonyliv.com/")
        } else if (lowerUrl.contains("fancode")) {
            headers.putIfAbsent("Origin", "https://www.fancode.com")
            headers.putIfAbsent("Referer", "https://www.fancode.com/")
        } else if (lowerUrl.contains("tataplay")) {
            headers.putIfAbsent("Origin", "https://watch.tataplay.com")
            headers.putIfAbsent("Referer", "https://watch.tataplay.com/")
        } else if (lowerUrl.contains("yupp") || lowerUrl.contains("yupptv")) {
            headers.putIfAbsent("Origin", "https://www.yupptv.com")
            headers.putIfAbsent("Referer", "https://www.yupptv.com/")
        }
    }

    private fun normalizeFormat1(url: String, keyId: String, cookie: String, userAgent: String, baseHeaders: Map<String, String>): NormalizedStream {
        val streamHeaders = mutableMapOf<String, String>()
        val drmHeaders = mutableMapOf<String, String>()
        streamHeaders["User-Agent"] = JIO_STB_UA
        drmHeaders["User-Agent"] = if (userAgent.isNotBlank()) userAgent else JIO_STB_UA
        streamHeaders["Accept"] = "*/*"
        streamHeaders["Connection"] = "keep-alive"
        var cleanUrl = url.substringBefore("|").trim()
        var extractedCookie = cookie
        if (url.contains("|")) {
            url.substringAfter("|").split("&").forEach { pair ->
                val kv = pair.split("=", limit = 2)
                if (kv.size == 2) {
                    val k = kv[0].trim().lowercase()
                    val v = Uri.decode(kv[1].trim())
                    if (k == "cookie" && extractedCookie.isBlank()) extractedCookie = v
                    else streamHeaders[kv[0].trim()] = v
                }
            }
        }
        if (extractedCookie.isNotBlank()) streamHeaders["Cookie"] = extractedCookie
        streamHeaders.putAll(baseHeaders)
        drmHeaders.putAll(baseHeaders)
        while (cleanUrl.endsWith("?") || cleanUrl.endsWith("&")) {
            cleanUrl = cleanUrl.substring(0, cleanUrl.length - 1)
        }
        val proxyDrmUrl = if (keyId.isNotBlank() && keyId.startsWith("http", true)) LocalStreamProxy.createProxyLicenseUrl(keyId.trim(), drmHeaders, "F1") else null
        return NormalizedStream(
            proxyStreamUrl = LocalStreamProxy.createProxyUrl(cleanUrl, streamHeaders, "F1"),
            mimeType = mimeTypeFor(cleanUrl),
            headers = streamHeaders,
            drmScheme = if (proxyDrmUrl != null) C.CLEARKEY_UUID else null,
            proxyDrmLicenseUrl = proxyDrmUrl,
            drmHeaders = drmHeaders,
            localJwk = null
        )
    }

    private fun normalizeFormat2(url: String, keyId: String, cookie: String, userAgent: String, baseHeaders: Map<String, String>): NormalizedStream {
        val streamHeaders = mutableMapOf<String, String>()
        val drmHeaders = mutableMapOf<String, String>()
        val cleanUrl = url.trim()

        streamHeaders["User-Agent"] = sanitizeUa(userAgent, cleanUrl)
        streamHeaders["Accept"] = "*/*"
        streamHeaders["Connection"] = "keep-alive"
        if (cookie.isNotBlank()) streamHeaders["Cookie"] = cookie
        streamHeaders.putAll(baseHeaders)
        applyDomainSpoofing(cleanUrl, streamHeaders)

        // FIX: If it's an allinonereborn PHP proxy link, return it DIRECTLY without local proxy wrapping
        val finalStreamUrl = if (cleanUrl.contains("allinonereborn2.online")) {
            cleanUrl
        } else {
            LocalStreamProxy.createProxyUrl(cleanUrl, streamHeaders, "F2")
        }

        val proxyDrmUrl = if (keyId.isNotBlank() && keyId.startsWith("http", true)) LocalStreamProxy.createProxyLicenseUrl(keyId.trim(), drmHeaders, "F2") else null
        return NormalizedStream(
            proxyStreamUrl = finalStreamUrl,
            mimeType = mimeTypeFor(cleanUrl),
            headers = streamHeaders,
            drmScheme = if (proxyDrmUrl != null) C.CLEARKEY_UUID else null,
            proxyDrmLicenseUrl = proxyDrmUrl,
            drmHeaders = drmHeaders,
            localJwk = null
        )
    }

    private fun normalizeFormat3(url: String, keyId: String, key: String, cookie: String, userAgent: String, baseHeaders: Map<String, String>): NormalizedStream {
        val streamHeaders = mutableMapOf<String, String>()
        streamHeaders["User-Agent"] = JIO_STB_UA
        streamHeaders["Accept"] = "*/*"
        streamHeaders["Connection"] = "keep-alive"
        var cleanUrl = url.trim()
        if (cookie.isNotBlank()) {
            streamHeaders["Cookie"] = cookie
            if (cookie.contains("__hdnea__") && !cleanUrl.contains("__hdnea__")) {
                val token = cookie.split(";").firstOrNull { it.contains("__hdnea__") }?.trim()
                if (token != null) {
                    val sep = if (cleanUrl.contains("?")) "&" else "?"
                    cleanUrl = "$cleanUrl$sep$token"
                }
            }
        }
        streamHeaders.putAll(baseHeaders)
        var activeKid = keyId.trim()
        var activeK = key.trim()
        if (activeKid.contains(":") && activeK.isBlank()) { val p = activeKid.split(":"); activeKid = p[0]; activeK = p[1] }
        else if (activeK.contains(":") && activeKid.isBlank()) { val p = activeK.split(":"); activeKid = p[0]; activeK = p[1] }
        var localJwk: String? = null
        if (activeKid.length >= 16 && activeK.length >= 16) {
            localJwk = try {
                val jwk = JSONObject().apply {
                    put("keys", JSONArray().put(JSONObject().apply {
                        put("kty", "oct")
                        put("k", Base64.encodeToString(hexToBytes(activeK), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
                        put("kid", Base64.encodeToString(hexToBytes(activeKid), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
                    }))
                }
                jwk.toString()
            } catch (e: Exception) { null }
        }
        return NormalizedStream(
            proxyStreamUrl = LocalStreamProxy.createProxyUrl(cleanUrl, streamHeaders, "F3"),
            mimeType = mimeTypeFor(cleanUrl),
            headers = streamHeaders,
            drmScheme = if (localJwk != null) C.CLEARKEY_UUID else null,
            proxyDrmLicenseUrl = null,
            drmHeaders = null,
            localJwk = localJwk
        )
    }

    private fun normalizeFormat4(url: String, keyId: String, key: String, cookie: String, userAgent: String, baseHeaders: Map<String, String>): NormalizedStream {
        val streamHeaders = mutableMapOf<String, String>()

        var cleanUrl = url.substringBefore("|").trim()
        var extractedCookie = cookie
        var extractedUa = userAgent

        if (url.contains("|")) {
            url.substringAfter("|").split("&").forEach { pair ->
                val kv = pair.split("=", limit = 2)
                if (kv.size == 2) {
                    val k = kv[0].trim().lowercase()
                    val v = Uri.decode(kv[1].trim())
                    if (k == "cookie" && extractedCookie.isBlank()) extractedCookie = v
                    else if (k == "user-agent" || k == "http-user-agent") extractedUa = v
                    else streamHeaders[kv[0].trim()] = v
                }
            }
        }

        streamHeaders["User-Agent"] = sanitizeUa(extractedUa, cleanUrl)
        streamHeaders["Accept"] = "*/*"
        streamHeaders["Connection"] = "keep-alive"

        if (extractedCookie.isNotBlank()) {
            streamHeaders["Cookie"] = extractedCookie

            if (extractedCookie.contains("__hdnea__") && !cleanUrl.contains("__hdnea__")) {
                val token = if (extractedCookie.contains(";")) {
                    extractedCookie.split(";").firstOrNull { it.contains("__hdnea__") }?.trim()
                } else {
                    extractedCookie.trim()
                }
                if (token != null && token.contains("__hdnea__")) {
                    val sep = if (cleanUrl.contains("?")) "&" else "?"
                    cleanUrl = "$cleanUrl$sep$token"
                }
            }
        }

        streamHeaders.putAll(baseHeaders)
        applyDomainSpoofing(cleanUrl, streamHeaders)

        var activeKid = keyId.trim()
        var activeK = key.trim()
        if (activeKid.contains(":") && activeK.isBlank()) { val p = activeKid.split(":"); activeKid = p[0]; activeK = p[1] }

        val proxyDrmUrl = if (activeKid.isNotBlank() && activeK.isNotBlank()) {
            LocalStreamProxy.createProxyLicenseUrl("$activeKid:$activeK", streamHeaders, "F4")
        } else if (activeKid.isNotBlank() && activeKid.contains(":")) {
            LocalStreamProxy.createProxyLicenseUrl(activeKid, streamHeaders, "F4")
        } else null

        return NormalizedStream(
            proxyStreamUrl = LocalStreamProxy.createProxyUrl(cleanUrl, streamHeaders, "F4"),
            mimeType = mimeTypeFor(cleanUrl),
            headers = streamHeaders,
            drmScheme = if (proxyDrmUrl != null) C.CLEARKEY_UUID else null,
            proxyDrmLicenseUrl = proxyDrmUrl,
            drmHeaders = streamHeaders,
            localJwk = null
        )
    }

    private fun normalizeFormat5(url: String, keyId: String, cookie: String, userAgent: String, baseHeaders: Map<String, String>): NormalizedStream {
        val streamHeaders = mutableMapOf<String, String>()
        var cleanUrl = url.substringBefore("|").trim()
        var extractedCookie = cookie
        var extractedUa = userAgent
        if (url.contains("|")) {
            url.substringAfter("|").split("&").forEach { pair ->
                val kv = pair.split("=", limit = 2)
                if (kv.size == 2) {
                    val k = kv[0].trim().lowercase()
                    val v = Uri.decode(kv[1].trim())
                    if (k == "cookie" && extractedCookie.isBlank()) extractedCookie = v
                    else if (k == "user-agent" || k == "http-user-agent") extractedUa = v
                    else streamHeaders[kv[0].trim()] = v
                }
            }
        }
        streamHeaders["User-Agent"] = sanitizeUa(extractedUa, cleanUrl)
        streamHeaders["Accept"] = "*/*"
        streamHeaders["Connection"] = "keep-alive"
        if (extractedCookie.isNotBlank()) streamHeaders["Cookie"] = extractedCookie
        streamHeaders.putAll(baseHeaders)
        applyDomainSpoofing(cleanUrl, streamHeaders)
        val proxyDrmUrl = if (keyId.isNotBlank() && keyId.contains(":")) {
            LocalStreamProxy.createProxyLicenseUrl(keyId.trim(), streamHeaders, "F5")
        } else null
        return NormalizedStream(
            proxyStreamUrl = LocalStreamProxy.createProxyUrl(cleanUrl, streamHeaders, "F5"),
            mimeType = mimeTypeFor(cleanUrl),
            headers = streamHeaders,
            drmScheme = if (proxyDrmUrl != null) C.CLEARKEY_UUID else null,
            proxyDrmLicenseUrl = proxyDrmUrl,
            drmHeaders = streamHeaders,
            localJwk = null
        )
    }

    private fun normalizeFormat6(url: String, keyId: String, cookie: String, userAgent: String, baseHeaders: Map<String, String>): NormalizedStream {
        val streamHeaders = mutableMapOf<String, String>()
        val drmHeaders = mutableMapOf<String, String>()
        val finalUa = if (userAgent.isNotBlank() && !userAgent.startsWith("@")) userAgent else JIO_STB_UA
        streamHeaders["User-Agent"] = finalUa
        drmHeaders["User-Agent"] = finalUa
        streamHeaders["Accept"] = "*/*"
        drmHeaders["Accept"] = "*/*"
        streamHeaders["Connection"] = "keep-alive"
        var cleanUrl = url.trim()
        var extractedCookie = cookie
        if (extractedCookie.isNotBlank()) {
            streamHeaders["Cookie"] = extractedCookie
            drmHeaders["Cookie"] = extractedCookie
            val token = if (extractedCookie.contains(";")) {
                extractedCookie.split(";").firstOrNull { it.contains("__hdnea__") }?.trim()
            } else {
                extractedCookie.trim()
            }
            if (token != null && token.contains("__hdnea__")) {
                val sep = if (cleanUrl.contains("?")) "&" else "?"
                cleanUrl = "$cleanUrl$sep$token"
            }
        }
        streamHeaders.putAll(baseHeaders)
        drmHeaders.putAll(baseHeaders)
        applyDomainSpoofing(cleanUrl, streamHeaders)
        val proxyDrmUrl = if (keyId.isNotBlank() && keyId.startsWith("http", true)) {
            LocalStreamProxy.createProxyLicenseUrl(keyId.trim(), drmHeaders, "F6")
        } else null
        return NormalizedStream(
            proxyStreamUrl = LocalStreamProxy.createProxyUrl(cleanUrl, streamHeaders, "F6"),
            mimeType = mimeTypeFor(cleanUrl),
            headers = streamHeaders,
            drmScheme = if (proxyDrmUrl != null) C.CLEARKEY_UUID else null,
            proxyDrmLicenseUrl = proxyDrmUrl,
            drmHeaders = drmHeaders,
            localJwk = null
        )
    }

    private fun normalizeGeneric(url: String, keyId: String, key: String, cookie: String, userAgent: String, baseHeaders: Map<String, String>): NormalizedStream {
        val streamHeaders = mutableMapOf<String, String>()
        var cleanUrl = if (url.contains("?|")) url.substringBefore("?|") else url.substringBefore("|")
        cleanUrl = cleanUrl.trim()
        if (cleanUrl.endsWith("?")) cleanUrl = cleanUrl.substring(0, cleanUrl.length - 1)

        var extractedUa = userAgent
        var extractedCookie = cookie

        val pipePart = if (url.contains("?|")) url.substringAfter("?|") else if (url.contains("|")) url.substringAfter("|") else ""
        if (pipePart.isNotBlank()) {
            pipePart.split("&").forEach { pair ->
                val kv = pair.split("=", limit = 2)
                if (kv.size == 2) {
                    val k = kv[0].trim().lowercase()
                    val rawV = kv[1].trim()
                    val v = if (k == "cookie") rawV else Uri.decode(rawV)
                    when (k) {
                        "cookie" -> if (extractedCookie.isBlank()) extractedCookie = v
                        "user-agent", "http-user-agent" -> extractedUa = v
                        "origin" -> streamHeaders["Origin"] = v
                        "referer", "http-referrer", "http-referer" -> streamHeaders["Referer"] = v
                        else -> streamHeaders[kv[0].trim()] = v
                    }
                }
            }
        }

        streamHeaders["User-Agent"] = sanitizeUa(extractedUa, cleanUrl)
        if (extractedCookie.isNotBlank()) streamHeaders["Cookie"] = extractedCookie
        streamHeaders.putAll(baseHeaders)
        applyDomainSpoofing(cleanUrl, streamHeaders)

        if (cleanUrl.contains("hotstar.com", true)) {
            val hdntlToken = if (extractedCookie.contains("hdntl=")) {
                extractedCookie.split(";").firstOrNull { it.contains("hdntl=") }?.trim()
            } else {
                extractedCookie.trim()
            }
            if (!hdntlToken.isNullOrBlank() && hdntlToken.contains("hdntl=") && !cleanUrl.contains("hdntl=")) {
                val sep = if (cleanUrl.contains("?")) "&" else "?"
                cleanUrl = "$cleanUrl$sep$hdntlToken"
            }
        }

        val proxyDrmUrl = if (keyId.isNotBlank() && keyId.startsWith("http", true)) LocalStreamProxy.createProxyLicenseUrl(keyId.trim(), streamHeaders, "GEN") else null

        val drmScheme = if (proxyDrmUrl != null) {
            if (cleanUrl.contains("sunnxt") || cleanUrl.contains("tataplay")) C.CLEARKEY_UUID else C.WIDEVINE_UUID
        } else null

        return NormalizedStream(
            proxyStreamUrl = LocalStreamProxy.createProxyUrl(cleanUrl, streamHeaders, "GEN"),
            mimeType = mimeTypeFor(cleanUrl),
            headers = streamHeaders,
            drmScheme = drmScheme,
            proxyDrmLicenseUrl = proxyDrmUrl,
            drmHeaders = streamHeaders,
            localJwk = null
        )
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val bytes = ByteArray(clean.length / 2)
        for (i in bytes.indices) bytes[i] = ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
        return bytes
    }
}