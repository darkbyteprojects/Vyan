package com.vyan.xtreamplayer.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object StreamResolver {
    fun isDirectStream(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mpd") || lower.contains(".mp4") ||
                lower.contains(".ts") || lower.contains(".mkv") || lower.contains(".webm")
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    suspend fun resolveEmbedUrl(context: Context, embedUrl: String): String? = suspendCancellableCoroutine { cont ->
        Handler(Looper.getMainLooper()).post {
            val webView = WebView(context)
            webView.settings.apply {
                javaScriptEnabled = true
                loadsImagesAutomatically = true
                domStorageEnabled = true
                allowContentAccess = true
                allowFileAccess = true
                mixedContentMode = 0
                mediaPlaybackRequiresUserGesture = false

                // FIX: Force Firefox Desktop UA to bypass Cloudflare Bot Protection
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; rv:78.0) Gecko/20100101 Firefox/78.0"
            }

            var isResolved = false
            fun finish(url: String?) {
                if (!isResolved) {
                    isResolved = true
                    try { webView.destroy() } catch (_: Exception) {}
                    cont.resume(url)
                }
            }

            webView.addJavascriptInterface(object : Any() {
                @JavascriptInterface
                fun onStreamUrlFound(url: String) { finish(url) }
            }, "StreamBridge")

            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val reqUrl = request.url.toString()
                    if (isDirectStream(reqUrl)) {
                        Handler(Looper.getMainLooper()).post { finish(reqUrl) }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!isResolved) {
                            try { view.evaluateJavascript("(function() { if (typeof playbackURL !== 'undefined' && playbackURL) { window.StreamBridge.onStreamUrlFound(playbackURL); } })();", null) } catch (_: Exception) {}
                        }
                    }, 500)
                }
            }
            webView.webChromeClient = WebChromeClient()
            webView.loadUrl(embedUrl)

            // Extension uses a 30 second timeout for the sniffer
            Handler(Looper.getMainLooper()).postDelayed({ finish(null) }, 30000)
        }
    }
}