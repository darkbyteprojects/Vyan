package com.vyan.xtreamplayer.utils

import android.net.Uri
import android.util.Base64
import androidx.media3.common.C
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
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

object StreamFormatNormalizer {

    private const val JIO_STB_UA = "JioTV.Plus/2.8.4_2076/StreamFlex(StreamFlex;JioSTB) JioTvPlus-AndroidTv"
    private const val JIO_MOBILE_UA = "plaYtv/7.1.3 (Linux;Android 13) - @Vortex Tv - ExoPlayerLib/824.0"
    private const val GENERIC_BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val IPTV_DEFAULT_UA = "ExoPlayer/2.18.1 (Linux;Android 12) ExoPlayerLib/2.18.1"

    fun normalize(
        url: String,
        keyId: String,
        key: String,
        cookie: String,
        userAgent: String,
        baseHeaders: Map<String, String>,
        bypassProxy: Boolean = true, // Default to true to let ExoPlayer handle native redirects
        forceDomainHeaders: Boolean = false
    ): NormalizedStream {
        val streamHeaders = mutableMapOf<String, String>()

        var cleanUrl = if (url.contains("?|")) url.substringBefore("?|") else url.substringBefore("|")
        cleanUrl = cleanUrl.trim()
        while (cleanUrl.endsWith("?") || cleanUrl.endsWith("&")) {
            cleanUrl = cleanUrl.substring(0, cleanUrl.length - 1)
        }

        var extractedUa = userAgent
        var extractedCookie = cookie

        streamHeaders.putAll(baseHeaders)

        val pipePart = if (url.contains("?|")) url.substringAfter("?|") else if (url.contains("|")) url.substringAfter("|") else ""
        if (pipePart.isNotBlank()) {
            pipePart.split("&").forEach { pair ->
                val kv = pair.split("=", limit = 2)
                if (kv.size == 2) {
                    val k = kv[0].trim().lowercase()
                    val rawV = kv[1].trim()

                    var v = Uri.decode(rawV)
                    if (v.contains("%")) {
                        try { v = URLDecoder.decode(v, "UTF-8") } catch (_: Exception) {}
                    }
                    v = v.trim(' ', '|', '=')

                    when (k) {
                        "cookie", "http-cookie" -> if (extractedCookie.isBlank()) extractedCookie = v
                        "user-agent", "http-user-agent" -> extractedUa = v
                        "origin", "http-origin" -> streamHeaders["Origin"] = v
                        "referer", "http-referrer", "http-referer" -> streamHeaders["Referer"] = v
                        "accept" -> streamHeaders["Accept"] = v
                        "authorization" -> streamHeaders["Authorization"] = v
                        "key", "keyid", "kid", "licenseurl" -> { }
                    }
                }
            }
        }

        // FORCE A VALID USER AGENT to prevent 400 Bad Request on CDNs like Rutube
        streamHeaders["User-Agent"] = sanitizeUa(extractedUa, cleanUrl, forceDomainHeaders)
        streamHeaders["Accept"] = "*/*"

        if (extractedCookie.isNotBlank()) {
            streamHeaders["Cookie"] = extractedCookie
        }

        applyDomainSpoofing(cleanUrl, streamHeaders, forceDomainHeaders)

        val drmHeaders = mutableMapOf<String, String>().apply { putAll(streamHeaders) }

        var activeKid = keyId.trim()
        var activeK = key.trim()

        if (activeKid.contains(":") && activeK.isBlank() && !activeKid.startsWith("http", true)) {
            val p = activeKid.split(":", limit = 2)
            activeKid = p[0].trim()
            activeK = p[1].trim()
        } else if (activeK.contains(":") && activeKid.isBlank() && !activeK.startsWith("http", true)) {
            val p = activeK.split(":", limit = 2)
            activeKid = p[0].trim()
            activeK = p[1].trim()
        }

        var localJwk: String? = null
        var proxyDrmUrl: String? = null
        var drmScheme: UUID? = null

        if (activeKid.isNotBlank() && activeK.isNotBlank() && !activeKid.startsWith("http", true)) {
            val b64Kid = toBase64Url(activeKid)
            val b64Key = toBase64Url(activeK)

            if (b64Kid != null && b64Key != null) {
                drmScheme = C.CLEARKEY_UUID
                localJwk = JSONObject().apply {
                    put("keys", JSONArray().put(JSONObject().apply {
                        put("kty", "oct")
                        put("kid", b64Kid)
                        put("k", b64Key)
                    }))
                    put("type", "temporary")
                }.toString()
            }
        } else if (activeKid.isNotBlank() && activeKid.startsWith("http", true)) {
            proxyDrmUrl = LocalStreamProxy.createProxyLicenseUrl(activeKid, drmHeaders, "UNI")
            drmScheme = if (cleanUrl.contains("sunnxt") || cleanUrl.contains("tataplay")) C.CLEARKEY_UUID else C.WIDEVINE_UUID
        }

        // Route through proxy only if strictly necessary, otherwise ExoPlayer's DefaultHttpDataSource handles cookies better
        val finalStreamUrl = if (bypassProxy) {
            cleanUrl
        } else {
            LocalStreamProxy.createProxyUrl(cleanUrl, streamHeaders, "UNI")
        }

        return NormalizedStream(
            proxyStreamUrl = finalStreamUrl,
            mimeType = mimeTypeFor(cleanUrl),
            headers = streamHeaders,
            drmScheme = drmScheme,
            proxyDrmLicenseUrl = proxyDrmUrl,
            drmHeaders = drmHeaders,
            localJwk = localJwk
        )
    }

    private fun toBase64Url(input: String): String? {
        val clean = input.replace("-", "").replace(" ", "").replace("\"", "").replace("'", "").trim()
        if (clean.isBlank()) return null
        return try {
            if (clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } && clean.length % 2 == 0) {
                val bytes = ByteArray(clean.length / 2)
                for (i in bytes.indices) {
                    bytes[i] = ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
                }
                Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            } else {
                val bytes = try { Base64.decode(clean, Base64.URL_SAFE or Base64.NO_WRAP) } catch (_: Exception) { Base64.decode(clean, Base64.DEFAULT) }
                Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            }
        } catch (_: Exception) { null }
    }

    private fun mimeTypeFor(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains(".mpd") -> androidx.media3.common.MimeTypes.APPLICATION_MPD
            lower.contains(".m3u8") -> androidx.media3.common.MimeTypes.APPLICATION_M3U8
            lower.contains(".mp4") -> androidx.media3.common.MimeTypes.VIDEO_MP4
            lower.contains(".ts") -> androidx.media3.common.MimeTypes.VIDEO_MP2T
            lower.contains(".mkv") -> androidx.media3.common.MimeTypes.VIDEO_MATROSKA
            else -> androidx.media3.common.MimeTypes.APPLICATION_M3U8
        }
    }

    private fun sanitizeUa(ua: String, url: String, force: Boolean = false): String {
        val lowerUa = ua.lowercase()
        val lowerUrl = url.lowercase()

        // Strict check: if UA is tiny (like "Mozilla") or blank, use a robust browser string
        val isJunkUa = ua.isBlank() || ua.length < 15 || lowerUa == "mozilla" || lowerUa.contains("sayan10")

        val isJio = lowerUrl.contains("jio")
        if (isJio) {
            return if (!force && !isJunkUa) ua else if (lowerUrl.contains("_mob")) JIO_MOBILE_UA else JIO_STB_UA
        }

        if (!force && ua.isNotBlank() && !isJunkUa) {
            return ua
        }

        if (lowerUrl.contains("sonyliv") || lowerUrl.contains("fancode") || lowerUrl.contains("rutube")) {
            return GENERIC_BROWSER_UA
        }

        return if (isJunkUa || force) IPTV_DEFAULT_UA else ua
    }

    private fun applyDomainSpoofing(url: String, headers: MutableMap<String, String>, force: Boolean = false) {
        val lowerUrl = url.lowercase()

        fun set(key: String, value: String) {
            if (force) headers[key] = value else headers.putIfAbsent(key, value)
        }

        if (lowerUrl.contains("jio")) {
            set("Origin", "https://jiotv.com")
            set("Referer", "https://jiotv.com/")
        } else if (lowerUrl.contains("rutube")) {
            set("Origin", "https://rutube.ru")
            set("Referer", "https://rutube.ru/")
        } else if (lowerUrl.contains("sonyliv")) {
            set("Origin", "https://www.sonyliv.com")
            set("Referer", "https://www.sonyliv.com/")
        } else {
            try {
                val uri = java.net.URI(url)
                val host = uri.host
                val scheme = uri.scheme ?: "https"
                if (!host.isNullOrBlank()) {
                    val rootOrigin = "$scheme://$host"
                    set("Origin", rootOrigin)
                    set("Referer", "$rootOrigin/")
                }
            } catch (_: Exception) {}
        }
    }
}