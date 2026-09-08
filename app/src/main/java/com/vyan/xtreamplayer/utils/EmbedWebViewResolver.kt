package com.vyan.xtreamplayer.utils

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object EmbedWebViewResolver {

    // Identifies if the URL is already a direct playable media file
    fun isDirectStream(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") ||
                lower.contains(".mpd") ||
                lower.contains(".ts") ||
                lower.contains(".mp4") ||
                lower.contains(".mkv")
    }

    /**
     * Headless WebView sniffer.
     * Loads a web player iframe in the background and intercepts network traffic
     * until it finds the hidden manifest file, then returns it.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolveEmbedUrl(context: Context, embedUrl: String): String? = withContext(Dispatchers.Main) {
        var targetUrl = embedUrl.trim()

        // Safely extract the actual URL if the backend sent a raw JSON embed token
        if (targetUrl.startsWith("{") && targetUrl.contains("\"api\"")) {
            try {
                val jsonObj = JSONObject(targetUrl)
                targetUrl = jsonObj.optString("api", targetUrl)
            } catch (_: Exception) {}
        }

        if (isDirectStream(targetUrl)) return@withContext targetUrl

        var resolvedUrl: String? = null
        val latch = CountDownLatch(1)

        val webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            // Force desktop UA to bypass mobile-web restrictions often found on embeds
            settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val url = request?.url?.toString() ?: ""

                    // As soon as the WebView attempts to download the stream manifest, we hijack the URL
                    if (isDirectStream(url) && resolvedUrl == null) {
                        resolvedUrl = url
                        latch.countDown() // Release the coroutine lock
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }
        }

        webView.loadUrl(targetUrl)

        // Wait on an IO thread for a maximum of 12 seconds for the manifest to load
        withContext(Dispatchers.IO) {
            latch.await(12, TimeUnit.SECONDS)
        }

        // Clean up memory immediately
        webView.destroy()

        return@withContext resolvedUrl
    }
}