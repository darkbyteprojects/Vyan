package com.vyan.xtreamplayer.utils

import android.net.Uri
import android.util.Base64
import androidx.media3.common.C
import java.util.UUID

/**
 * Holds the perfectly formatted, proxy-routed stream data ready for ExoPlayer.
 */
data class NormalizedStream(
    val proxyStreamUrl: String,
    val mimeType: String,
    val headers: Map<String, String>,
    val drmScheme: UUID?,
    val proxyDrmLicenseUrl: String?,
    val localJwk: String?
)

object StreamFormatNormalizer {

    fun normalize(
        url: String,
        keyId: String,
        key: String,
        cookie: String,
        baseHeaders: Map<String, String>
    ): NormalizedStream {
        val reqProperties = mutableMapOf<String, String>()
        reqProperties["Accept"] = "*/*"
        reqProperties["Connection"] = "keep-alive"

        if (cookie.isNotBlank()) reqProperties["Cookie"] = cookie
        reqProperties.putAll(baseHeaders)

        // ---------------------------------------------------------
        // RULE 1: Pipe Header Extraction (e.g., URL|Header=Value)
        // ---------------------------------------------------------
        var cleanUrl = url
        if (cleanUrl.contains("|")) {
            val headerPart = cleanUrl.substringAfter("|")
            headerPart.split("&").forEach { pair ->
                val kv = pair.split("=", limit = 2)
                if (kv.size == 2) reqProperties[kv[0].trim()] = kv[1].trim()
            }
            cleanUrl = cleanUrl.substringBefore("|").trim()
        }

        while (cleanUrl.endsWith("?") || cleanUrl.endsWith("&")) {
            cleanUrl = cleanUrl.substring(0, cleanUrl.length - 1)
        }

        // ---------------------------------------------------------
        // RULE 2: Akamai / CDN Token Injection (Query String fixes)
        // ---------------------------------------------------------
        if (cookie.contains("__hdnea__") && !cleanUrl.contains("__hdnea__")) {
            val token = cookie.split(";").firstOrNull { it.contains("__hdnea__") }?.trim()
            if (token != null) cleanUrl += (if (cleanUrl.contains("?")) "&" else "?") + token
        }
        if (cookie.contains("hdnts") && !cleanUrl.contains("hdnts")) {
            val token = cookie.split(";").firstOrNull { it.contains("hdnts") }?.trim()
            if (token != null) cleanUrl += (if (cleanUrl.contains("?")) "&" else "?") + token
        }

        // ---------------------------------------------------------
        // RULE 3: Domain-Specific Origin/Referer Spoofing
        // ---------------------------------------------------------
        val lowerUrl = cleanUrl.lowercase()
        when {
            lowerUrl.contains("slivcdn.com") || lowerUrl.contains("sonyliv") -> {
                reqProperties.putIfAbsent("Origin", "https://www.sonyliv.com/")
                reqProperties.putIfAbsent("Referer", "https://www.sonyliv.com/")
            }
            lowerUrl.contains("jiotv") || lowerUrl.contains("jio.com") -> {
                reqProperties.putIfAbsent("Origin", "https://www.jiotv.com/")
                reqProperties.putIfAbsent("Referer", "https://www.jiotv.com/")
            }
            lowerUrl.contains("tataplay") || lowerUrl.contains("watch.tataplay.com") -> {
                reqProperties.putIfAbsent("Origin", "https://watch.tataplay.com/")
                reqProperties.putIfAbsent("Referer", "https://watch.tataplay.com/")
            }
            lowerUrl.contains("hotstar") -> {
                reqProperties.putIfAbsent("Origin", "https://www.hotstar.com/")
                reqProperties.putIfAbsent("Referer", "https://www.hotstar.com/")
            }
            else -> {
                try {
                    val uri = Uri.parse(cleanUrl)
                    uri.host?.let {
                        reqProperties.putIfAbsent("Origin", "https://$it/")
                        reqProperties.putIfAbsent("Referer", "https://$it/")
                    }
                } catch (e: Exception) {}
            }
        }

        // ---------------------------------------------------------
        // RULE 4: DRM Key Format Normalization
        // ---------------------------------------------------------
        var activeKeyId = keyId.trim()
        var activeKey = key.trim()
        if (activeKey.contains(":") && activeKeyId.isBlank()) {
            val parts = activeKey.split(":")
            if (parts.size == 2) {
                activeKeyId = parts[0].trim()
                activeKey = parts[1].trim()
            }
        }

        val isWidevine = activeKeyId.contains("widevine", ignoreCase = true) || activeKey.contains("widevine", ignoreCase = true)
        val drmScheme = if (isWidevine) C.WIDEVINE_UUID else C.CLEARKEY_UUID

        var proxyDrmLicenseUrl: String? = null
        var localJwk: String? = null

        // STRICT PROXY RULE FOR DRM
        if (activeKeyId.isNotBlank() && activeKeyId.startsWith("http", ignoreCase = true)) {
            proxyDrmLicenseUrl = LocalStreamProxy.createProxyLicenseUrl(activeKeyId, reqProperties)
        } else if (activeKeyId.length >= 16 && activeKey.length >= 16) {
            localJwk = buildClearKeyJwk(activeKeyId, activeKey)
        }

        // ---------------------------------------------------------
        // RULE 5: Strict Proxy Routing for the Video Stream
        // ---------------------------------------------------------
        val mimeType = if (lowerUrl.contains(".mpd")) {
            androidx.media3.common.MimeTypes.APPLICATION_MPD
        } else {
            androidx.media3.common.MimeTypes.APPLICATION_M3U8
        }

        val isLocalFile = cleanUrl.startsWith("file://", ignoreCase = true) || cleanUrl.startsWith("content://", ignoreCase = true)

        val proxyStreamUrl = if (!isLocalFile) {
            LocalStreamProxy.createProxyUrl(cleanUrl, reqProperties)
        } else {
            cleanUrl
        }

        return NormalizedStream(
            proxyStreamUrl = proxyStreamUrl,
            mimeType = mimeType,
            headers = reqProperties,
            drmScheme = if (proxyDrmLicenseUrl != null || localJwk != null) drmScheme else null,
            proxyDrmLicenseUrl = proxyDrmLicenseUrl,
            localJwk = localJwk
        )
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
}