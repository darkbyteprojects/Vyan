package com.vyan.xtreamplayer.stream

import java.util.UUID

enum class PlaybackMethod {
    DIRECT_HTTP,  // Standard HLS/TS (Needs OkHttp Interceptor)
    LOCAL_PROXY,  // DASH/MPD (Needs Ktor manifest rewriting)
    WEB_RESOLVER  // Hidden embed (Needs Headless WebView sniffer)
}

data class StreamProfile(
    val originalInput: String,
    var playUrl: String = originalInput,
    val headers: MutableMap<String, String> = mutableMapOf(),
    var method: PlaybackMethod = PlaybackMethod.DIRECT_HTTP,
    var drmScheme: UUID? = null,
    var drmLicenseUrl: String? = null,
    var clearKeyJwk: String? = null
)