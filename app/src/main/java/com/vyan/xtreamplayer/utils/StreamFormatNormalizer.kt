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

    fun normalize(
        url: String, keyId: String, key: String, cookie: String, userAgent: String, baseHeaders: Map<String, String>
    ): NormalizedStream {
        val format = detectFormat(url, keyId, cookie)
        return when (format) {
            PlaylistFormat.FORMAT_1_M3U_PIPE -> normalizeFormat1(url, keyId, cookie, userAgent, baseHeaders)
            PlaylistFormat.FORMAT_2_M3U_QUERY -> normalizeFormat2(url, keyId, cookie, userAgent, baseHeaders)
            PlaylistFormat.FORMAT_3_JSON_CLEARKEY -> normalizeFormat3(url, keyId, key, cookie, userAgent, baseHeaders)
            PlaylistFormat.UNKNOWN -> normalizeGeneric(url, keyId, key, cookie, userAgent, baseHeaders)
        }
    }

    private fun detectFormat(url: String, keyId: String, cookie: String): PlaylistFormat {
        return when {
            url.contains("|Cookie=", true) || url.contains("|cookie=", true) || url.contains("&xxx=") || keyId.contains("streamflexsmm.in", true) -> PlaylistFormat.FORMAT_1_M3U_PIPE
            keyId.contains(".php", true) || keyId.contains("keyid=", true) || keyId.contains("allinonereborn2.online", true) -> PlaylistFormat.FORMAT_2_M3U_QUERY
            cookie.contains("__hdnea__") && !url.contains("__hdnea__") -> PlaylistFormat.FORMAT_3_JSON_CLEARKEY
            else -> PlaylistFormat.UNKNOWN
        }
    }

    private fun sanitizeUa(ua: String): String = if (ua.isBlank() || ua.startsWith("@")) JIO_STB_UA else ua

    // =========================================================================================
    // LANE 1: FORMAT 1 (StreamFlex / Jio Pipe)
    // =========================================================================================
    private fun normalizeFormat1(url: String, keyId: String, cookie: String, userAgent: String, baseHeaders: Map<String, String>): NormalizedStream {
        val streamHeaders = mutableMapOf<String, String>()
        val drmHeaders = mutableMapOf<String, String>()

        val activeUa = sanitizeUa(userAgent)
        streamHeaders["User-Agent"] = activeUa
        drmHeaders["User-Agent"] = activeUa
        streamHeaders["Accept"] = "*/*"
        streamHeaders["Connection"] = "keep-alive"

        var cleanUrl = url.substringBefore("|").trim()
        var extractedCookie = cookie

        // Safely extract pipe attributes without overwriting EXTHTTP cookie
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
        val proxyStreamUrl = LocalStreamProxy.createProxyUrl(cleanUrl, streamHeaders, "F1")

        return NormalizedStream(
            proxyStreamUrl = proxyStreamUrl,
            mimeType = if (cleanUrl.lowercase().contains(".mpd")) androidx.media3.common.MimeTypes.APPLICATION_MPD else androidx.media3.common.MimeTypes.APPLICATION_M3U8,
            headers = streamHeaders,
            drmScheme = if (proxyDrmUrl != null) C.CLEARKEY_UUID else null,
            proxyDrmLicenseUrl = proxyDrmUrl,
            drmHeaders = drmHeaders,
            localJwk = null
        )
    }

    // =========================================================================================
    // LANE 2: FORMAT 2 (Allinone PHP Fetcher)
    // =========================================================================================
    private fun normalizeFormat2(url: String, keyId: String, cookie: String, userAgent: String, baseHeaders: Map<String, String>): NormalizedStream {
        val streamHeaders = mutableMapOf<String, String>()
        val activeUa = sanitizeUa(userAgent)
        streamHeaders["User-Agent"] = activeUa
        if (cookie.isNotBlank()) streamHeaders["Cookie"] = cookie
        streamHeaders.putAll(baseHeaders)

        val proxyDrmUrl = if (keyId.isNotBlank() && keyId.startsWith("http", true)) LocalStreamProxy.createProxyLicenseUrl(keyId.trim(), streamHeaders, "F2") else null
        val proxyStreamUrl = LocalStreamProxy.createProxyUrl(url.trim(), streamHeaders, "F2")

        return NormalizedStream(
            proxyStreamUrl = proxyStreamUrl,
            mimeType = if (url.lowercase().contains(".mpd")) androidx.media3.common.MimeTypes.APPLICATION_MPD else androidx.media3.common.MimeTypes.APPLICATION_M3U8,
            headers = streamHeaders,
            drmScheme = if (proxyDrmUrl != null) C.CLEARKEY_UUID else null,
            proxyDrmLicenseUrl = proxyDrmUrl,
            drmHeaders = streamHeaders,
            localJwk = null
        )
    }

    // =========================================================================================
    // LANE 3: FORMAT 3 (JSON Catchup with Local Hex Key)
    // =========================================================================================
    private fun normalizeFormat3(url: String, keyId: String, key: String, cookie: String, userAgent: String, baseHeaders: Map<String, String>): NormalizedStream {
        val streamHeaders = mutableMapOf<String, String>()
        streamHeaders["User-Agent"] = sanitizeUa(userAgent)
        if (cookie.isNotBlank()) streamHeaders["Cookie"] = cookie
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
                    put("type", "temporary")
                }
                jwk.toString()
            } catch (e: Exception) { null }
        }

        return NormalizedStream(
            proxyStreamUrl = LocalStreamProxy.createProxyUrl(url.trim(), streamHeaders, "F3"),
            mimeType = if (url.lowercase().contains(".mpd")) androidx.media3.common.MimeTypes.APPLICATION_MPD else androidx.media3.common.MimeTypes.APPLICATION_M3U8,
            headers = streamHeaders,
            drmScheme = if (localJwk != null) C.CLEARKEY_UUID else null,
            proxyDrmLicenseUrl = null,
            drmHeaders = null,
            localJwk = localJwk
        )
    }

    private fun normalizeGeneric(url: String, keyId: String, key: String, cookie: String, userAgent: String, baseHeaders: Map<String, String>): NormalizedStream {
        val streamHeaders = mutableMapOf<String, String>()
        streamHeaders["User-Agent"] = sanitizeUa(userAgent)
        if (cookie.isNotBlank()) streamHeaders["Cookie"] = cookie
        streamHeaders.putAll(baseHeaders)

        val proxyDrmUrl = if (keyId.isNotBlank() && keyId.startsWith("http", true)) LocalStreamProxy.createProxyLicenseUrl(keyId.trim(), streamHeaders, "GEN") else null

        return NormalizedStream(
            proxyStreamUrl = LocalStreamProxy.createProxyUrl(url.trim(), streamHeaders, "GEN"),
            mimeType = if (url.lowercase().contains(".mpd")) androidx.media3.common.MimeTypes.APPLICATION_MPD else androidx.media3.common.MimeTypes.APPLICATION_M3U8,
            headers = streamHeaders,
            drmScheme = if (proxyDrmUrl != null) C.CLEARKEY_UUID else null,
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