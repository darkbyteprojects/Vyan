@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.vyan.xtreamplayer.utils

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource

object ExoServerDiagnostic {

    fun extractExactError(error: PlaybackException): String {
        var currentCause: Throwable? = error.cause

        while (currentCause != null) {
            if (currentCause is HttpDataSource.InvalidResponseCodeException) {
                val code = currentCause.responseCode
                val message = currentCause.responseMessage ?: "No Message"
                val headers = currentCause.headerFields

                val serverName = headers["Server"]?.firstOrNull() ?: "CDN"
                return "HTTP $code: $serverName Rejected Request\nDetail: $message"
            }
            if (currentCause is HttpDataSource.HttpDataSourceException) {
                return "Network Connection Failed: ${currentCause.message}"
            }
            currentCause = currentCause.cause
        }

        return "Playback Error: ${error.errorCodeName}"
    }
}