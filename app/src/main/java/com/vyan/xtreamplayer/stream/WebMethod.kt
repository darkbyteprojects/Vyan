package com.vyan.xtreamplayer.stream

import android.content.Context
import com.vyan.xtreamplayer.stream.StreamProfile
import com.vyan.xtreamplayer.utils.StreamResolver

object WebMethod {
    suspend fun resolve(context: Context, profile: StreamProfile): StreamProfile {
        // 1. Sniff the network traffic for the true manifest
        val resolvedUrl = StreamResolver.resolveEmbedUrl(context, profile.playUrl)

        // 2. If resolution succeeds, update the URL. If it fails, keep the original to gracefully error out.
        if (resolvedUrl != null) {
            profile.playUrl = resolvedUrl
        }
        return profile
    }
}