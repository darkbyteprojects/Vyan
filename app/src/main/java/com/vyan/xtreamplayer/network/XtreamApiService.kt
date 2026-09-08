package com.vyan.xtreamplayer.network

import com.vyan.xtreamplayer.models.LiveCategory
import com.vyan.xtreamplayer.models.XtreamLiveChannelModel
import com.vyan.xtreamplayer.models.LoginResponse
import com.vyan.xtreamplayer.models.SeriesCategory
import com.vyan.xtreamplayer.models.SeriesDetailsResponse
import com.vyan.xtreamplayer.models.SeriesItem
import com.vyan.xtreamplayer.models.VodCategory
import com.vyan.xtreamplayer.models.VodMovie
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

interface XtreamService {
    @GET
    suspend fun authenticate(
        @Url fullUrl: String,
        @Query("username") username: String,
        @Query("password") pass: String
    ): Response<LoginResponse>

    @GET
    suspend fun getLiveCategories(
        @Url fullUrl: String,
        @Query("username") username: String,
        @Query("password") pass: String,
        @Query("action") action: String = "get_live_categories"
    ): Response<List<LiveCategory>>

    @GET
    suspend fun getLiveStreams(
        @Url fullUrl: String,
        @Query("username") username: String,
        @Query("password") pass: String,
        @Query("category_id") categoryId: String? = null,
        @Query("action") action: String = "get_live_streams"
    ): Response<List<XtreamLiveChannelModel>>

    @GET
    suspend fun getVodCategories(
        @Url fullUrl: String,
        @Query("username") username: String,
        @Query("password") pass: String,
        @Query("action") action: String = "get_vod_categories"
    ): Response<List<VodCategory>>

    @GET
    suspend fun getVodStreams(
        @Url fullUrl: String,
        @Query("username") username: String,
        @Query("password") pass: String,
        @Query("category_id") categoryId: String? = null,
        @Query("action") action: String = "get_vod_streams"
    ): Response<List<VodMovie>>

    @GET
    suspend fun getSeriesCategories(
        @Url fullUrl: String,
        @Query("username") username: String,
        @Query("password") pass: String,
        @Query("action") action: String = "get_series_categories"
    ): Response<List<SeriesCategory>>

    @GET
    suspend fun getSeries(
        @Url fullUrl: String,
        @Query("username") username: String,
        @Query("password") pass: String,
        @Query("category_id") categoryId: String? = null,
        @Query("action") action: String = "get_series"
    ): Response<List<SeriesItem>>

    @GET
    suspend fun getSeriesInfo(
        @Url fullUrl: String,
        @Query("username") username: String,
        @Query("password") pass: String,
        @Query("series_id") seriesId: Int,
        @Query("action") action: String = "get_series_info"
    ): Response<SeriesDetailsResponse>
}

object XtreamApi {
    private fun getUnsafeOkHttpClient(): OkHttpClient {
        return try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, SecureRandom())

            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        } catch (e: Exception) {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }

    private val retrofit = Retrofit.Builder()
        .baseUrl("http://localhost/")
        .client(getUnsafeOkHttpClient())
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val service: XtreamService = retrofit.create(XtreamService::class.java)

    fun cleanBaseUrl(baseUrl: String): String {
        var clean = baseUrl.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "http://$clean"
        }
        if (clean.endsWith("/player_api.php")) {
            clean = clean.substringBefore("/player_api.php")
        } else if (clean.endsWith("player_api.php")) {
            clean = clean.substringBefore("player_api.php")
        }
        if (!clean.endsWith("/")) {
            clean += "/"
        }
        return clean
    }

    fun formatApiUrl(baseUrl: String): String {
        val clean = cleanBaseUrl(baseUrl)
        return "${clean}player_api.php"
    }

    fun buildLiveStreamUrl(serverUrl: String, user: String, pass: String, streamId: Int, extension: String = "m3u8"): String {
        val clean = cleanBaseUrl(serverUrl)
        return "${clean}live/$user/$pass/$streamId.$extension"
    }

    fun buildMovieStreamUrl(serverUrl: String, user: String, pass: String, streamId: Int, extension: String = "mp4"): String {
        val clean = cleanBaseUrl(serverUrl)
        return "${clean}movie/$user/$pass/$streamId.$extension"
    }

    fun buildSeriesStreamUrl(serverUrl: String, user: String, pass: String, episodeId: String, extension: String = "mp4"): String {
        val clean = cleanBaseUrl(serverUrl)
        return "${clean}series/$user/$pass/$episodeId.$extension"
    }
}

