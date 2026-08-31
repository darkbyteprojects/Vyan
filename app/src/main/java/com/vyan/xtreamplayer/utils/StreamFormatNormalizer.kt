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

object StreamFormatNormalizer {

    private const val JIO_STB_UA = "JioTV.Plus/2.8.4_2076/StreamFlex(StreamFlex;JioSTB) JioTvPlus-AndroidTv"
    private const val JIO_MOBILE_UA = "plaYtv/7.1.3 (Linux;Android 13) - @Vortex Tv - ExoPlayerLib/824.0"
    private const val GENERIC_BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val IPTV_DEFAULT_UA = "ExoPlayer/2.18.1 (Linux;Android 12) ExoPlayerLib/2.18.1"

    fun normalize(
        url: String, keyId: String, key: String, cookie: String, userAgent: String, baseHeaders: Map<String, String>
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
        streamHeaders["Accept"] = "*/*"
        streamHeaders["Connection"] = "keep-alive"

        if (extractedCookie.isNotBlank()) {
            streamHeaders["Cookie"] = extractedCookie
        }

        applyDomainSpoofing(cleanUrl, streamHeaders)

        val drmHeaders = mutableMapOf<String, String>().apply { putAll(streamHeaders) }

        if (extractedCookie.isNotBlank()) {
            val hdneaToken = extractedCookie.split(";").firstOrNull { it.contains("__hdnea__") }?.trim()
            if (hdneaToken != null && !cleanUrl.contains("__hdnea__")) {
                val sep = if (cleanUrl.contains("?")) "&" else "?"
                cleanUrl = "$cleanUrl$sep$hdneaToken"
            }
            val hdntlToken = extractedCookie.split(";").firstOrNull { it.contains("hdntl=") }?.trim()
            if (hdntlToken != null && !cleanUrl.contains("hdntl=") && cleanUrl.contains("hotstar.com", true)) {
                val sep = if (cleanUrl.contains("?")) "&" else "?"
                cleanUrl = "$cleanUrl$sep$hdntlToken"
            }
        }

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
            drmScheme = C.CLEARKEY_UUID
            localJwk = try {
                val kidBytes = hexToBytes(activeKid)
                val keyBytes = hexToBytes(activeK)

                // Strict Base64URL encoding without padding
                val kidB64 = Base64.encodeToString(kidBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                val keyB64 = Base64.encodeToString(keyBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

                // Constructing the exact JSON structure expected by ExoPlayer ClearKey
                val jwkObject = JSONObject().apply {
                    put("kty", "oct")
                    put("kid", kidB64)
                    put("k", keyB64)
                }

                JSONObject().apply {
                    put("keys", JSONArray().put(jwkObject))
                }.toString()
            } catch (e: Exception) {
                null
            }
        } else if (activeKid.isNotBlank() && activeKid.startsWith("http", true)) {
            proxyDrmUrl = LocalStreamProxy.createProxyLicenseUrl(activeKid, drmHeaders, "UNI")
            drmScheme = if (cleanUrl.contains("sunnxt") || cleanUrl.contains("tataplay")) C.CLEARKEY_UUID else C.WIDEVINE_UUID
        } else if (activeKid.isNotBlank() && activeKid.contains(":")) {
            proxyDrmUrl = LocalStreamProxy.createProxyLicenseUrl(activeKid, drmHeaders, "UNI")
            drmScheme = C.CLEARKEY_UUID
        }

        // Bypass local proxy for MPD and ClearKey streams to prevent chunk modification
        val finalStreamUrl = if (cleanUrl.contains(".mpd", true) || localJwk != null) {
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

    private fun sanitizeUa(ua: String, url: String): String {
        val lowerUa = ua.lowercase()
        val lowerUrl = url.lowercase()

        val isJio = lowerUrl.contains("jio")
        if (isJio) {
            return if (lowerUrl.contains("_mob")) JIO_MOBILE_UA else JIO_STB_UA
        }

        // FIX: Honor the playlist's provided user-agent for Hotstar/SonyLiv instead of forcing desktop browser UA
        if (ua.isNotBlank() && !lowerUa.contains("sayan10") && !lowerUa.contains("ott navigator")) {
            return ua
        }

        if (lowerUrl.contains("sonyliv") || lowerUrl.contains("fancode")) {
            return GENERIC_BROWSER_UA
        }

        if (ua.isBlank() || lowerUa.contains("sayan10") || lowerUa.contains("ott navigator")) return IPTV_DEFAULT_UA
        return ua
    }
    private fun applyDomainSpoofing(url: String, headers: MutableMap<String, String>) {
        val lowerUrl = url.lowercase()
        if (lowerUrl.contains("jio")) {
            headers["Origin"] = "https://www.jiotv.com"
            headers["Referer"] = "https://www.jiotv.com/"
        } else if (lowerUrl.contains("sonyliv")) {
            headers["Origin"] = "https://www.sonyliv.com"
            headers["Referer"] = "https://www.sonyliv.com/"
        } else if (lowerUrl.contains("fancode")) {
            headers["Origin"] = "https://www.fancode.com"
            headers["Referer"] = "https://www.fancode.com/"
        } else if (lowerUrl.contains("tataplay")) {
            headers["Origin"] = "https://watch.tataplay.com"
            headers["Referer"] = "https://watch.tataplay.com/"
        } else if (lowerUrl.contains("yupp") || lowerUrl.contains("yupptv")) {
            headers.putIfAbsent("Origin", "https://www.yupptv.com")
            headers.putIfAbsent("Referer", "https://www.yupptv.com/")
        } else if (lowerUrl.contains("hotstar")) {
            headers["Origin"] = "https://www.hotstar.com"
            headers["Referer"] = "https://www.hotstar.com/"
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val bytes = ByteArray(clean.length / 2)
        for (i in bytes.indices) {
            bytes[i] = ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
        return bytes
    }
}