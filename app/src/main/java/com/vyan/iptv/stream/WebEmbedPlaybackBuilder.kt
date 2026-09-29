package com.vyan.iptv.stream

import android.content.Context
import com.vyan.iptv.utils.EmbedWebViewResolver

object WebEmbedPlaybackBuilder {
    suspend fun resolve(context: Context, profile: StreamProfile): StreamProfile {
        val resolvedUrl = EmbedWebViewResolver.resolveEmbedUrl(context, profile.playUrl)

        if (resolvedUrl != null) {
            profile.playUrl = resolvedUrl
        }
        return profile
    }
}