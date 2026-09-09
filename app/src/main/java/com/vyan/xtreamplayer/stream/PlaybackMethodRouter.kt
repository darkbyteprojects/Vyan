package com.vyan.xtreamplayer.stream

import android.net.Uri
import android.util.Base64
import androidx.media3.common.C

object PlaybackMethodRouter {

    const val DEFAULT_BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    const val JIO_STB_UA =
        "JioTVPlus/2.8.4_2076/StreamFlex(StreamFlex;JioSTB) JioTvPlus-AndroidTv"
    const val JIO_MOBILE_UA =
        "plaYtv/7.1.3 (Linux;Android 13) - @Vortex Tv - ExoPlayerLib/824.0"
    const val HOTSTAR_UA =
        "Hotstar;in.startv.hotstar/25.02.24.8.11169@Premium Plugx(Android/15)"

    fun decideAndRoute(profile: StreamProfile): StreamProfile {
        val routed = profile.copy(
            headers = HashMap(profile.headers)
        )

        routed.playUrl = routed.playUrl.trim().trimEnd('?', '&')
        configureHeaders(routed)
        configureDrm(routed)

        val cleanUrlLower = routed.playUrl.lowercase()
        routed.method = when {
            cleanUrlLower.contains(".mpd") -> PlaybackMethod.LOCAL_PROXY
            cleanUrlLower.contains("embed") || cleanUrlLower.contains("player.php") -> PlaybackMethod.WEB_RESOLVER
            else -> PlaybackMethod.DIRECT_HTTP
        }

        return routed
    }

    private fun configureHeaders(profile: StreamProfile) {
        val existingUa = profile.headers["User-Agent"] ?: profile.headers["user-agent"]
        val urlLower = profile.playUrl.lowercase()

        // 1. Configure User-Agent: Block known IPTV player names and force native UAs for specific CDNs
        val isBlacklistedUa = existingUa.isNullOrBlank() ||
                existingUa.contains("OTT Navigator", ignoreCase = true) ||
                existingUa.contains("VLC", ignoreCase = true)

        val selectedUa = when {
            urlLower.contains("hotstar") || urlLower.contains("hotstar-cdn") -> HOTSTAR_UA
            urlLower.contains("jiotvbpkstb") || urlLower.contains("jio.com") -> JIO_STB_UA
            urlLower.contains("mblive") -> JIO_MOBILE_UA
            !isBlacklistedUa -> existingUa!!
            else -> DEFAULT_BROWSER_UA
        }
        profile.headers["User-Agent"] = selectedUa

        // 2. Check if UA represents a native STB / Mobile device
        val isNativeApp = selectedUa.contains("JioSTB", ignoreCase = true) ||
                selectedUa.contains("plaYtv", ignoreCase = true) ||
                selectedUa.contains("Hotstar", ignoreCase = true) ||
                selectedUa.contains("ExoPlayer", ignoreCase = true)

        if (isNativeApp) {
            // Native STB/Apps generally do not send browser Origin/Referer headers
            // (Unless explicitly forced below)
            profile.headers.remove("Origin")
            profile.headers.remove("Referer")
            profile.headers.remove("origin")
            profile.headers.remove("referer")
        }

        // 3. Intelligently generate Origin/Referer for Web CDNs or explicitly mapped streams
        val existingOrigin = profile.headers["Origin"] ?: profile.headers["origin"]
        val existingReferer = profile.headers["Referer"] ?: profile.headers["referer"]

        // Force Hotstar headers if URL requires it
        if (urlLower.contains("hotstar")) {
            profile.headers["Origin"] = "https://www.hotstar.com"
            profile.headers["Referer"] = "https://www.hotstar.com/"
        } else if (!isNativeApp) {
            if (existingOrigin.isNullOrBlank()) {
                var generatedOrigin: String? = null

                if (!existingReferer.isNullOrBlank()) {
                    try {
                        val refUri = android.net.Uri.parse(existingReferer)
                        if (!refUri.host.isNullOrBlank()) {
                            generatedOrigin = "${refUri.scheme ?: "https"}://${refUri.host}"
                        }
                    } catch (_: Exception) {}
                }

                if (generatedOrigin.isNullOrBlank()) {
                    try {
                        val streamUri = android.net.Uri.parse(profile.playUrl)
                        if (!streamUri.host.isNullOrBlank()) {
                            generatedOrigin = "${streamUri.scheme ?: "https"}://${streamUri.host}"
                        }
                    } catch (_: Exception) {}
                }

                if (!generatedOrigin.isNullOrBlank()) {
                    profile.headers["Origin"] = generatedOrigin
                    profile.headers.putIfAbsent("Referer", "$generatedOrigin/")
                }
            }
        }
    }

    private fun configureDrm(profile: StreamProfile) {
        val kid = profile.headers["EXTRACTED_KID"]
        val key = profile.headers["EXTRACTED_KEY"]
        val explicitType = profile.headers["EXTRACTED_LICENSE_TYPE"]?.lowercase()

        if (!profile.drmLicenseUrl.isNullOrBlank()) {
            val urlLower = profile.drmLicenseUrl!!.lowercase()
            profile.drmScheme = when {
                explicitType == "clearkey" || urlLower.contains("clearkey") -> C.CLEARKEY_UUID
                explicitType == "playready" || urlLower.contains("playready") -> C.PLAYREADY_UUID
                else -> C.WIDEVINE_UUID
            }
        } else if (!kid.isNullOrBlank() && !key.isNullOrBlank() && !kid.startsWith("http", true)) {
            profile.drmScheme = C.CLEARKEY_UUID
            profile.clearKeyJwk = buildClearKeyJwk(kid, key)
            profile.headers.remove("EXTRACTED_KID")
            profile.headers.remove("EXTRACTED_KEY")
        }
    }

    private fun buildClearKeyJwk(kid: String, key: String): String {
        val b64Kid = toBase64Url(kid)
        val b64Key = toBase64Url(key)
        return """{"keys":[{"kty":"oct","k":"$b64Key","kid":"$b64Kid"}],"type":"temporary"}"""
    }

    private fun toBase64Url(hex: String): String {
        val cleanHex = hex.replace("-", "").replace(":", "").trim()
        return if (cleanHex.matches(Regex("^[0-9a-fA-F]+$")) && cleanHex.length % 2 == 0) {
            val bytes = ByteArray(cleanHex.length / 2)
            for (i in bytes.indices) {
                bytes[i] = cleanHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        } else {
            cleanHex.replace("+", "-").replace("/", "_").replace("=", "")
        }
    }
}