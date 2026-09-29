package com.vyan.iptv.stream

import java.net.URLDecoder
import org.json.JSONObject

object PlaybackLinkParser {

    fun parse(rawInput: String): StreamProfile {
        // PROOF OF INPUT: This will log exactly what the parser receives when you tap a channel.
        android.util.Log.e("PARSER_DEBUG", "RAW INPUT RECEIVED BY PARSER: \n$rawInput")

        val profile = StreamProfile(originalInput = rawInput)
        val cleanInput = rawInput.trim()

        if (cleanInput.isBlank()) return profile

        // 1. UNIVERSAL BRUTE-FORCE TOKEN EXTRACTOR
        // This ignores all formatting rules. If an Akamai token exists ANYWHERE in the string, it forces it into the Cookie header.
        val decodedInput = safeUrlDecode(cleanInput)
        val tokenRegex = Regex("""(__hdnea__=st=\d+~exp=\d+~acl=[^~&|'"]+~hmac=[a-fA-F0-9]+)""")
        val tokenMatch = tokenRegex.find(decodedInput) ?: tokenRegex.find(cleanInput)
        if (tokenMatch != null) {
            profile.headers["Cookie"] = tokenMatch.groupValues[1]
            android.util.Log.e("PARSER_DEBUG", "BRUTE-FORCE SUCCESS! Found Cookie: ${profile.headers["Cookie"]}")
        }

        // 2. STANDARD PARSING (JSON or Pipe format)
        if (cleanInput.startsWith("{") && cleanInput.endsWith("}")) {
            try {
                val json = JSONObject(cleanInput)
                profile.playUrl = json.optString("url", profile.playUrl).trim()

                val headersObj = json.optJSONObject("headers")
                headersObj?.keys()?.forEach { key ->
                    val cleanKey = key.trim()
                    val cleanVal = headersObj.getString(key).trim()
                    if (!cleanKey.equals("xxx", ignoreCase = true) && cleanVal.isNotBlank()) {
                        val finalKey = when (cleanKey.lowercase()) {
                            "cookie" -> "Cookie"
                            "user-agent" -> "User-Agent"
                            "referer" -> "Referer"
                            "origin" -> "Origin"
                            else -> cleanKey
                        }
                        // Only put if absent, so we don't overwrite the perfect brute-force cookie
                        profile.headers.putIfAbsent(finalKey, cleanVal)
                    }
                }

                json.optString("license_url").takeIf { it.isNotBlank() }?.let { profile.drmLicenseUrl = it }
                json.optString("key_id").takeIf { it.isNotBlank() }?.let { profile.headers["EXTRACTED_KID"] = it }
                json.optString("key").takeIf { it.isNotBlank() }?.let { profile.headers["EXTRACTED_KEY"] = it }
            } catch (_: Exception) {}

        } else {
            val parts = cleanInput.split("|", limit = 2)
            profile.playUrl = parts[0].trim()

            if (parts.size > 1) {
                val headerParams = parts[1].split("&")
                for (param in headerParams) {
                    if (!param.contains("=")) continue
                    val rawKey = param.substringBefore("=").trim()
                    val rawValue = param.substringAfter("=").trim()

                    if (rawKey.isBlank() || rawKey.equals("xxx", ignoreCase = true)) continue

                    val decodedValue = safeUrlDecode(rawValue)

                    when (rawKey.lowercase()) {
                        "user-agent", "useragent" -> profile.headers.putIfAbsent("User-Agent", decodedValue)
                        "cookie", "http-cookie" -> profile.headers.putIfAbsent("Cookie", decodedValue)
                        "referer", "referrer" -> profile.headers.putIfAbsent("Referer", decodedValue)
                        "origin" -> profile.headers.putIfAbsent("Origin", decodedValue)
                        "keyid", "kid" -> profile.headers.putIfAbsent("EXTRACTED_KID", decodedValue)
                        "key", "clearkey" -> profile.headers.putIfAbsent("EXTRACTED_KEY", decodedValue)
                        "licenseurl", "license_url" -> profile.drmLicenseUrl = decodedValue
                        "licensetype", "license_type" -> profile.headers.putIfAbsent("EXTRACTED_LICENSE_TYPE", decodedValue)
                        else -> {
                            if (!rawKey.startsWith("xxx", ignoreCase = true)) {
                                profile.headers.putIfAbsent(rawKey, decodedValue)
                            }
                        }
                    }
                }
            }
        }

        // 3. CLEAN UP BASE URL
        var finalUrl = profile.playUrl
        if (finalUrl.contains("__hdnea__=")) {
            finalUrl = finalUrl.substringBefore("?__hdnea__").substringBefore("&__hdnea__")
        }
        profile.playUrl = finalUrl.trimEnd('?', '&')

        return profile
    }

    private fun safeUrlDecode(value: String): String {
        return try {
            URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        } catch (e: Exception) {
            try {
                val fixed = value.replace(Regex("%(?![0-9a-fA-F]{2})"), "%25")
                URLDecoder.decode(fixed, "UTF-8")
            } catch (e2: Exception) {
                value
            }
        }.trim()
    }
}