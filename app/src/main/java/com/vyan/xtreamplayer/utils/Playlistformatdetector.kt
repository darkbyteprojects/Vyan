package com.vyan.xtreamplayer.utils

/**
 * Looks at the raw playlist text/JSON exactly as downloaded or picked from
 * disk and decides which [PlaylistFormat] it is, BEFORE any parsing happens.
 *
 * This is a cheap "sniffing" pass — a handful of indexOf/contains checks,
 * no full JSON decode. Once the format is known, [M3uParser] hands off to
 * the matching dedicated parser and never falls back to guesswork.
 */
object PlaylistFormatDetector {

    fun detect(rawContent: String): PlaylistFormat {
        val trimmed = rawContent.trim()
        if (trimmed.isEmpty()) return PlaylistFormat.UNKNOWN

        return when {
            looksLikeJsonClearkey(trimmed) -> PlaylistFormat.FORMAT_3_JSON_CLEARKEY
            looksLikeM3u(trimmed) -> detectM3uVariant(trimmed)
            else -> PlaylistFormat.UNKNOWN
        }
    }

    private fun looksLikeJsonClearkey(trimmed: String): Boolean {
        if (!(trimmed.startsWith("[") || trimmed.startsWith("{"))) return false
        // Format 3 objects always carry both "keyId" and "key" (the raw
        // clearkey pair) alongside a "url" field. Cheap substring sniff is
        // enough to route to the JSON parser; the parser itself validates
        // structure properly.
        return trimmed.contains("\"keyId\"") && trimmed.contains("\"key\"") && trimmed.contains("\"url\"")
    }

    private fun looksLikeM3u(trimmed: String): Boolean {
        return trimmed.startsWith("#EXTM3U") || trimmed.contains("#EXTINF")
    }

    private fun detectM3uVariant(trimmed: String): PlaylistFormat {
        // Format 1's stream lines carry a literal "?|" right after the base
        // URL (base_url?|Cookie=...&xxx=...). This is a distinctive marker
        // that Format 2 never produces.
        val hasPipeQueryMarker = Regex("""\?\|""").containsMatchIn(trimmed)
        if (hasPipeQueryMarker) return PlaylistFormat.FORMAT_1_M3U_PIPE

        // Any other M3U carrying a KODIPROP license_key is treated as
        // Format 2 (the "plain query token" style). This is deliberately
        // the default/fallback M3U shape — most license-key playlists that
        // aren't the pipe-marker style land here.
        if (trimmed.contains("#KODIPROP:inputstream.adaptive.license_key=")) {
            return PlaylistFormat.FORMAT_2_M3U_QUERY
        }

        return PlaylistFormat.UNKNOWN
    }
}