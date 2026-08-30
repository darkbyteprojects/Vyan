package com.vyan.xtreamplayer.utils

/**
 * Identifies which of the known playlist source formats a raw playlist payload
 * (or an individual channel parsed from it) matches.
 *
 * IMPORTANT — why this exists as a hard split instead of one smart parser:
 * Each format gets its OWN parser (in [M3uParser]) and its OWN stream
 * normalizer (in [StreamFormatNormalizer]). They intentionally do NOT share
 * cleanup/URL-rewrite logic beyond trivial, format-agnostic helpers.
 *
 * Earlier attempts to handle all playlist variants with one "universal"
 * heuristic (guess the cookie from the URL OR from EXTHTTP, guess the query
 * shape, etc.) caused regressions where a fix aimed at one provider's quirks
 * silently broke another provider's stream. Keeping the format-specific code
 * paths separate — even when that means near-duplicate code — is the point:
 * changing Format 2's handling can never again break Format 1 or Format 3.
 */
enum class PlaylistFormat {
    /**
     * M3U playlist whose stream line carries the auth token as a literal
     * pipe-prefixed query fragment right after the base URL, e.g.:
     * `.../index.mpd?|Cookie=__hdnea__=...&xxx=...`
     * The real token normally also arrives via `#EXTHTTP:{"cookie":"..."}`.
     */
    FORMAT_1_M3U_PIPE,

    /**
     * M3U playlist whose stream line has the auth token appended as a plain
     * (often duplicated) query parameter, no "?|" marker, e.g.:
     * `.../index.mpd?__hdnea__=...&__hdnea__=...`
     */
    FORMAT_2_M3U_QUERY,

    /**
     * Raw JSON array of channel objects that carry an explicit ClearKey
     * `keyId` + `key` pair directly (no license server URL to call —
     * the key material is already known and must be turned into a local
     * ClearKey JWK for playback).
     */
    FORMAT_3_JSON_CLEARKEY,

    /**
     * Content didn't match any known signature. Falls back to a generic,
     * best-effort M3U parser / normalizer so unrecognized playlists still
     * have a chance to work, instead of failing outright.
     */
    UNKNOWN
}