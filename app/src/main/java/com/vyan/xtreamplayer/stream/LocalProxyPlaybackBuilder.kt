// In LocalProxyPlaybackBuilder.kt
@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.vyan.xtreamplayer.stream

import android.content.Context
import androidx.media3.exoplayer.source.MediaSource
import com.vyan.xtreamplayer.utils.LocalStreamProxy

object LocalProxyPlaybackBuilder {

    fun buildMediaSource(context: Context, profile: StreamProfile): MediaSource {
        LocalStreamProxy.start()

        // FIX: Ensure the Cookie exists in the headers map before generating the proxy URL
        val safeHeaders = profile.headers.toMutableMap()

        // If the URL contains the token but the headers map doesn't, extract and inject it
        if (!safeHeaders.containsKey("Cookie") && profile.playUrl.contains("__hdnea__=")) {
            val tokenString = profile.playUrl.substringAfter("?").split("&").firstOrNull { it.startsWith("__hdnea__=") }
            if (tokenString != null) {
                safeHeaders["Cookie"] = tokenString
            }
        }

        // 1. Proxy main media manifest using the guaranteed safeHeaders
        val proxyPlayUrl = LocalStreamProxy.createProxyUrl(profile.playUrl, safeHeaders, "UNI")

        val proxyDrmLicenseUrl = profile.drmLicenseUrl?.let { url ->
            if (url.startsWith("http", ignoreCase = true)) {
                LocalStreamProxy.createProxyLicenseUrl(url, safeHeaders, "UNI")
            } else {
                url
            }
        }

        val proxiedProfile = profile.copy(
            playUrl = proxyPlayUrl,
            drmLicenseUrl = proxyDrmLicenseUrl ?: profile.drmLicenseUrl
        )

        return DirectHttpPlaybackBuilder.buildMediaSource(context, proxiedProfile)
    }
}