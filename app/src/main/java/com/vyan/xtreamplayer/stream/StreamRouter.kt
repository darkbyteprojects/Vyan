package com.vyan.xtreamplayer.stream

import android.util.Base64
import androidx.media3.common.C
import com.vyan.xtreamplayer.stream.PlaybackMethod
import com.vyan.xtreamplayer.stream.StreamProfile
import org.json.JSONArray
import org.json.JSONObject

object StreamRouter {

    fun decideAndRoute(profile: StreamProfile): StreamProfile {
        val lowerUrl = profile.playUrl.lowercase()

        // 1. Decide Method: Check if stream is hidden in a Web Player
        val isDirectFile = lowerUrl.contains(".m3u8") || lowerUrl.contains(".mpd") || lowerUrl.contains(".ts") || lowerUrl.contains(".mkv") || lowerUrl.contains(".mp4")
        if (!isDirectFile) {
            profile.method = PlaybackMethod.WEB_RESOLVER
            return profile // Stop routing; resolver will provide a new URL later
        }

        // 2. Sanitize WAF Rules (Prevent 400/403 Errors)
        if (lowerUrl.contains("sonyliv") || lowerUrl.contains("fancode") || lowerUrl.contains("rutube")) {
            profile.headers["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }
        if (lowerUrl.contains("jio")) {
            profile.headers["Origin"] = "https://jiotv.com"
            profile.headers["Referer"] = "https://jiotv.com/"
        }

        // Default fallback UA if missing
        if (!profile.headers.containsKey("User-Agent")) {
            profile.headers["User-Agent"] = "ExoPlayer/2.18.1 (Linux;Android 12) ExoPlayerLib/2.18.1"
        }

        // 3. Process DRM
        val kid = profile.headers.remove("EXTRACTED_KID")
        val key = profile.headers.remove("EXTRACTED_KEY")

        if (!kid.isNullOrBlank() && !key.isNullOrBlank() && !kid.startsWith("http")) {
            profile.drmScheme = C.CLEARKEY_UUID
            profile.clearKeyJwk = buildClearKeyJwk(kid, key)
        } else if (!profile.drmLicenseUrl.isNullOrBlank()) {
            profile.drmScheme = if (lowerUrl.contains("tataplay")) C.CLEARKEY_UUID else C.WIDEVINE_UUID
        }

        // 4. Decide Method: Direct HTTP vs Local Proxy
        if (lowerUrl.contains(".mpd") && !lowerUrl.contains("jiotv")) {
            profile.method = PlaybackMethod.LOCAL_PROXY
        } else {
            profile.method = PlaybackMethod.DIRECT_HTTP
        }

        return profile
    }

    private fun buildClearKeyJwk(kid: String, key: String): String? {
        return try {
            val b64Kid = hexToBase64Url(kid)
            val b64Key = hexToBase64Url(key)
            JSONObject().apply {
                put("keys", JSONArray().put(JSONObject().apply {
                    put("kty", "oct")
                    put("k", b64Key)
                    put("kid", b64Kid)
                }))
                put("type", "temporary")
            }.toString()
        } catch (_: Exception) { null }
    }

    private fun hexToBase64Url(hex: String): String {
        val clean = hex.replace("-", "").replace(" ", "").trim()
        val bytes = ByteArray(clean.length / 2)
        for (i in bytes.indices) bytes[i] = ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}