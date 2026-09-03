package com.vyan.xtreamplayer.utils

import android.util.Log
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

class HeaderLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Log the target URL and all outgoing headers
        Log.d("OkHttpHeaderDebug", "--> SENDING REQUEST: ${request.url}")
        for ((key, value) in request.headers) {
            Log.d("OkHttpHeaderDebug", "    Header -> $key: $value")
        }

        val response = chain.proceed(request)

        Log.d("<-- RECEIVED RESPONSE", "${response.code} for ${response.request.url}")
        return response
    }
}

object NetworkClient {
    val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(45, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor(HeaderLoggingInterceptor()) // <--- Attach the logger here
            .build()
    }
}