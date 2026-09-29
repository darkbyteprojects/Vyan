package com.vyan.iptv.stream

import java.util.UUID

enum class PlaybackMethod {
    DIRECT_HTTP,
    LOCAL_PROXY,
    WEB_RESOLVER
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