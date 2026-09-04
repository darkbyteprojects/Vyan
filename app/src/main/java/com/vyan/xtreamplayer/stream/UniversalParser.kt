package com.vyan.xtreamplayer.stream

import android.net.Uri
import com.vyan.xtreamplayer.stream.StreamProfile
import org.json.JSONObject

object UniversalParser {

    fun parse(rawInput: String): StreamProfile {
        val profile = StreamProfile(originalInput = rawInput)
        var cleanUrl = rawInput.trim()

        // 1. Check for JSON Web Tokens (e.g., Cricsfree API embeds)
        if (cleanUrl.startsWith("{") && cleanUrl.contains("\"api\"")) {
            try {
                val jsonObj = JSONObject(cleanUrl)
                cleanUrl = jsonObj.optString("api", cleanUrl)
            } catch (_: Exception) {}
        }

        // 2. Parse Pipe-Delimited M3U Attributes (url|User-Agent=x&Cookie=y)
        if (cleanUrl.contains("|")) {
            val parts = cleanUrl.split("|", limit = 2)
            profile.playUrl = parts[0].trim()

            val headerParams = parts[1].split("&")
            headerParams.forEach { param ->
                val kv = param.split("=", limit = 2)
                if (kv.size == 2) {
                    val key = kv[0].trim().lowercase()
                    val value = Uri.decode(kv[1].trim())

                    when (key) {
                        "user-agent" -> profile.headers["User-Agent"] = value
                        "cookie" -> profile.headers["Cookie"] = value
                        "referer", "referrer" -> profile.headers["Referer"] = value
                        "origin" -> profile.headers["Origin"] = value
                        "key" -> profile.headers["EXTRACTED_KEY"] = value
                        "keyid", "kid" -> profile.headers["EXTRACTED_KID"] = value
                        "licenseurl" -> profile.drmLicenseUrl = value
                        "xxx" -> {} // Ignore WAF garbage blocks
                        else -> profile.headers[kv[0].trim()] = value
                    }
                }
            }
        } else {
            profile.playUrl = cleanUrl
        }

        return profile
    }
}