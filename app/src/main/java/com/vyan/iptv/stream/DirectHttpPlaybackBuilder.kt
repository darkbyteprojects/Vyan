@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.vyan.iptv.stream

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object DirectHttpPlaybackBuilder {

    fun buildMediaSource(context: Context, profile: StreamProfile): MediaSource {
        val extractedUa = profile.headers["User-Agent"] ?: profile.headers["user-agent"]

        // 1. Sanitize standard headers
        val safeHeaders = profile.headers.mapNotNull { (k, v) ->
            val cleanKey = k.trim()
            if (cleanKey.equals("User-Agent", ignoreCase = true) ||
                cleanKey.startsWith("EXTRACTED_", ignoreCase = true) ||
                cleanKey.equals("xxx", ignoreCase = true)) {
                return@mapNotNull null
            }

            val cleanVal = v.replace("\n", "").replace("\r", "").trim()
            if (cleanKey.isNotBlank()) cleanKey to cleanVal else null
        }.toMap().toMutableMap()

        safeHeaders.putIfAbsent("Accept-Language", "en-US,en;q=0.9")
        safeHeaders.putIfAbsent("Accept", "*/*")

        val localCookieJar = object : CookieJar {
            private val cache = mutableListOf<Cookie>()
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { cache.addAll(cookies) }
            override fun loadForRequest(url: HttpUrl): List<Cookie> = cache
        }

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(localCookieJar)
            .addInterceptor { chain ->
                val request = chain.request()
                android.util.Log.e("EXO_NETWORK", "Executing Request to: ${request.url}")
                android.util.Log.e("EXO_NETWORK", "Outbound Headers: \n${request.headers}")
                val response = chain.proceed(request)
                // FIX: Log the actual HTTP response code from the CDN
                android.util.Log.e("EXO_NETWORK", "CDN Response Status: ${response.code} for ${request.url}")
                response
            }
            .build()

        val okHttpFactory = OkHttpDataSource.Factory(okHttpClient)
            .setDefaultRequestProperties(safeHeaders)

        if (!extractedUa.isNullOrBlank()) {
            okHttpFactory.setUserAgent(extractedUa)
        }

        val defaultFactory = DefaultDataSource.Factory(context, okHttpFactory)

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(defaultFactory)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(3))

        // 2. Explicit MIME Type declarations
        val mediaItemBuilder = MediaItem.Builder().setUri(Uri.parse(profile.playUrl))
        if (profile.playUrl.contains(".mpd", ignoreCase = true)) {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
        } else if (profile.playUrl.contains(".m3u8", ignoreCase = true)) {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }

        // 3. DRM Configuration
        val drmLicenseUrl = profile.drmLicenseUrl
        val clearKeyJwk = profile.clearKeyJwk

        if (!drmLicenseUrl.isNullOrBlank()) {
            val drmProvider = DefaultDrmSessionManagerProvider()
            drmProvider.setDrmHttpDataSourceFactory(okHttpFactory)
            mediaSourceFactory.setDrmSessionManagerProvider(drmProvider)

            mediaItemBuilder.setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(profile.drmScheme ?: C.WIDEVINE_UUID)
                    .setLicenseUri(drmLicenseUrl)
                    .setMultiSession(true)
                    .setLicenseRequestHeaders(safeHeaders)
                    .build()
            )
        } else if (!clearKeyJwk.isNullOrBlank()) {
            val clearKeyDrmManager = DefaultDrmSessionManager.Builder()
                .setMultiSession(true)
                .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .build(LocalMediaDrmCallback(clearKeyJwk.toByteArray(Charsets.UTF_8)))

            mediaSourceFactory.setDrmSessionManagerProvider { clearKeyDrmManager }
            mediaItemBuilder.setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID).build()
            )
        }

        return mediaSourceFactory.createMediaSource(mediaItemBuilder.build())
    }
}