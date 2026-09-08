package com.vyan.xtreamplayer.stream

import android.content.Context
import com.vyan.xtreamplayer.utils.EmbedWebViewResolver

object WebEmbedPlaybackBuilder {
    suspend fun resolve(context: Context, profile: StreamProfile): StreamProfile {
        val resolvedUrl = EmbedWebViewResolver.resolveEmbedUrl(context, profile.playUrl)

        if (resolvedUrl != null) {
            profile.playUrl = resolvedUrl
        }
        return profile
    }
}