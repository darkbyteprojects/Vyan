package com.vyan.xtreamplayer.utils

import android.util.Log
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Thread-safe, in-memory, per-host CookieJar.
 *
 * Many Indian CDNs (SonyLIV, JioCinema, FanCode, Tata Play) issue a session cookie
 * on the very first manifest request and then require that same cookie on every
 * subsequent segment / license request. Without a CookieJar, OkHttp silently drops
 * any Set-Cookie header on the response, so those follow-up requests get rejected
 * (401/403) even though the original headers we sent were correct.
 *
 * This jar is shared by NetworkClient.defaultClient, so both direct playback and
 * the local Ktor proxy (which reuses the same client) benefit automatically -
 * no per-provider code needed.
 */
class PersistentCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val host = url.host
        val existing = store.getOrPut(host) { mutableListOf() }
        synchronized(existing) {
            for (cookie in cookies) {
                existing.removeAll { it.name == cookie.name && it.path == cookie.path }
                if (cookie.expiresAt > System.currentTimeMillis()) {
                    existing.add(cookie)
                }
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.host
        val existing = store[host] ?: return emptyList()
        val now = System.currentTimeMillis()
        return synchronized(existing) {
            existing.removeAll { it.expiresAt <= now }
            existing.filter { it.matches(url) }
        }
    }

    fun clear() = store.clear()
}

class HeaderLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Uncomment for deep network debugging if streams fail
        // Log.d("NetworkClient", "--> SENDING REQUEST: ${request.url}")
        /*
        for ((key, value) in request.headers) {
            Log.d("NetworkClient", "    Header -> $key: $value")
        }
        */

        val response = try {
            chain.proceed(request)
        } catch (e: Exception) {
            Log.e("NetworkClient", "Request Failed: ${request.url} - ${e.message}")
            throw e
        }

        // Log.d("NetworkClient", "<-- RECEIVED RESPONSE: ${response.code} for ${response.request.url}")
        return response
    }
}

object NetworkClient {
    // Shared across defaultClient and any client built via .newBuilder() (e.g. PlayerScreen's
    // universalClient), so cookie continuity holds everywhere in the app, including inside the
    // local proxy which reuses this same client instance.
    val cookieJar = PersistentCookieJar()

    // Universal, highly-resilient client for APIs, Scrapers, and the Ktor Proxy
    val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(cookieJar)
            .addInterceptor(HeaderLoggingInterceptor())
            .build()
    }
}