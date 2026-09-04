@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.vyan.xtreamplayer.stream

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource

object DirectMethod {
    fun buildMediaSource(context: Context, profile: StreamProfile): MediaSource {

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(profile.headers["User-Agent"] ?: "ExoPlayer/2.18.1")
            .setDefaultRequestProperties(profile.headers)
            .setAllowCrossProtocolRedirects(true)

        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)

        val lowerUrl = profile.playUrl.lowercase()
        val detectedMimeType = when {
            lowerUrl.contains(".mpd") -> MimeTypes.APPLICATION_MPD
            lowerUrl.contains(".mkv") -> MimeTypes.VIDEO_MATROSKA
            lowerUrl.contains(".mp4") -> MimeTypes.VIDEO_MP4
            lowerUrl.contains(".ts") -> MimeTypes.VIDEO_MP2T
            else -> MimeTypes.APPLICATION_M3U8
        }

        val mediaItemBuilder = MediaItem.Builder()
            .setUri(Uri.parse(profile.playUrl))
            .setMimeType(detectedMimeType)

        if (profile.clearKeyJwk != null) {
            val jwkBytes = profile.clearKeyJwk!!.toByteArray(Charsets.UTF_8)
            val drmManager = DefaultDrmSessionManager.Builder()
                .setMultiSession(true)
                .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .build(LocalMediaDrmCallback(jwkBytes))
            mediaSourceFactory.setDrmSessionManagerProvider { drmManager }
            mediaItemBuilder.setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID).build())

        } else if (profile.drmLicenseUrl != null && profile.drmScheme != null) {
            mediaItemBuilder.setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(profile.drmScheme!!)
                    .setLicenseUri(profile.drmLicenseUrl)
                    .setMultiSession(true)
                    .setLicenseRequestHeaders(profile.headers)
                    .build()
            )
        }

        return mediaSourceFactory.createMediaSource(mediaItemBuilder.build())
    }
}