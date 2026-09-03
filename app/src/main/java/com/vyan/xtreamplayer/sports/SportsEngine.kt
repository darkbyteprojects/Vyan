package com.vyan.xtreamplayer.sports

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import com.vyan.xtreamplayer.utils.NetworkClient
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class StreamOption(val title: String, val url: String)

data class LiveSportsEvent(
    val id: Int,
    val title: String,
    val image: String,
    val slug: String,
    val category: String,
    val startTime: String,
    val endTime: String,
    val streamOptions: List<StreamOption>,
    val isPublished: Boolean,
    val teamA: String,
    val teamB: String,
    val logoA: String,
    val logoB: String
)

object SportsEngine {
    private val client = NetworkClient.defaultClient
    private const val USER_AGENT_API = "Mozilla/5.0 Cricfy2/1.0"

    private val CONFIG_URL_1 = SportsCrypto.reveal("8894949093dacfcf83ce908c819994858bce98999acfd4cd838fcd988d8b93ce8a938f8e", 224)
    private val CONFIG_URL_2 = SportsCrypto.reveal("c3dfdfdbd8918484db85cccec5d1cfcedd85d3d2d1849f86c8c486d3c6c0d885c1d8c4c5", 171)

    private var cachedApiHost: String? = null
    private var cachedToken: String? = null
    private var cachedGetDataPrefix: String = "v2/"
    private var cachedLiveEventsSlug: String = "v2/events.txt"
    var serverTimeOffset: Long = 0L
        private set

    private fun updateTimeOffset(response: Response) {
        val dateHeader = response.header("Date") ?: return
        try {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
            format.timeZone = TimeZone.getTimeZone("GMT")
            val serverTimeMs = format.parse(dateHeader)?.time ?: return
            val localTimeMs = System.currentTimeMillis()
            serverTimeOffset = serverTimeMs - localTimeMs
        } catch (_: Exception) {}
    }

    private suspend fun fetchRemoteConfiguration() = withContext(Dispatchers.IO) {
        if (cachedApiHost != null) return@withContext

        val urlsToTry = listOf(CONFIG_URL_1, CONFIG_URL_2)

        for (url in urlsToTry) {
            if (url.isBlank()) continue
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT_API)
                    .header("Accept", "application/json, text/plain, */*")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    updateTimeOffset(response)
                    val content = response.body?.string()?.trim() ?: continue

                    val jsonStr = if (content.startsWith("{")) content else SportsCrypto.decryptConfig(content)
                    val configObj = JSONObject(jsonStr)

                    val apiUrl = configObj.optString("api_url", configObj.optString("api_host", ""))
                    if (apiUrl.isNotEmpty()) {
                        applyConfigData(configObj)
                        return@withContext
                    }
                }
            } catch (e: Exception) {
                Log.e("SportsEngine", "Config fetch failed: ${e.message}")
            }
        }
    }

    private fun applyConfigData(json: JSONObject) {
        // 1. Exact fallback chain for the primary API host
        val apiUrl = json.optString("api_url").takeIf { it.isNotBlank() }
            ?: json.optString("api_host").takeIf { it.isNotBlank() }
            ?: json.optString("backup_host").takeIf { it.isNotBlank() }
            ?: json.optString("api").takeIf { it.isNotBlank() }
            ?: json.optString("base_url", "https://cricfytv.net/")

        // 2. Resolve the specific getData base URL
        var base = json.optString("getdata_base_url", apiUrl)

        // 3. Clean trailing slashes and version paths
        base = base.trimEnd('/')
        if (base.endsWith("/v2")) {
            base = base.substringBeforeLast("/v2")
        }

        cachedApiHost = base.trimEnd('/')

        // 4. Token fallbacks
        cachedToken = json.optString("getdata_token",
            json.optString("get_data_token", SportsCrypto.GETDATA_DEFAULT_TOKEN))

        cachedGetDataPrefix = json.optString("getdata_path_prefix", "v2/")

        // 5. Exact fallback chain for the live events feed path
        cachedLiveEventsSlug = json.optString("events_path").takeIf { it.isNotBlank() }
            ?: json.optString("events_url").takeIf { it.isNotBlank() }
                    ?: json.optString("cric_live_url").takeIf { it.isNotBlank() }
                    ?: "v2/events.txt"
    }

    private suspend fun fetchData(slugPath: String): String? = withContext(Dispatchers.IO) {
        fetchRemoteConfiguration()

        // Handle absolute content override URLs
        if (slugPath.startsWith("http://", true) || slugPath.startsWith("https://", true)) {
            try {
                val request = Request.Builder().url(slugPath).header("User-Agent", USER_AGENT_API).build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    return@withContext response.body?.string()?.trim()
                }
            } catch (_: Exception) {}
            return@withContext null
        }

        val host = cachedApiHost ?: return@withContext null

        // Replicate `normalizeGetDataPath` from z9.q7.a.java
        var prefix = cachedGetDataPrefix.trimStart('/')
        if (prefix.isNotEmpty() && !prefix.endsWith("/")) prefix += "/"

        val cleanPath = slugPath.trimStart('/')
        val normalizedSlug = if (prefix.isNotEmpty() && !cleanPath.startsWith(prefix) && !cleanPath.startsWith("v2/")) {
            prefix + cleanPath
        } else {
            cleanPath
        }

        val token = cachedToken ?: SportsCrypto.GETDATA_DEFAULT_TOKEN
        val trueTimeMs = System.currentTimeMillis() + serverTimeOffset
        val timestamp = (trueTimeMs / 1000).toString()

        val encodedKey = SportsCrypto.xorHex(normalizedSlug)
        val encodedHmac = SportsCrypto.xorHex("$timestamp|$token")

        val targetUrl = "$host/getData.php?key=$encodedKey&hmac=$encodedHmac"

        try {
            val request = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", USER_AGENT_API)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string()?.trim() ?: return@withContext null

                if (bodyStr.isEmpty() || bodyStr.startsWith("<")) return@withContext null
                if (bodyStr.startsWith("{") || bodyStr.startsWith("[")) return@withContext bodyStr

                val decrypted = SportsCrypto.decryptV2Payload(bodyStr)
                if (decrypted != null) return@withContext decrypted

                return@withContext bodyStr
            }
        } catch (e: Exception) {
            Log.e("SportsEngine", "Data fetch failed for $slugPath: ${e.message}")
        }
        return@withContext null
    }

    suspend fun getLiveEvents(): List<LiveSportsEvent> = withContext(Dispatchers.IO) {
        try {
            fetchRemoteConfiguration()
            val decryptedJson = fetchData(cachedLiveEventsSlug) ?: return@withContext emptyList()

            val jsonArray = JSONArray(decryptedJson)
            val events = mutableListOf<LiveSportsEvent>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)

                val eventStr = obj.optString("event", "")
                val eventObj = if (eventStr.isNotBlank()) JSONObject(eventStr) else obj

                val isVisible = eventObj.optBoolean("visible", obj.optBoolean("visible", true))
                if (!isVisible) continue

                val details = eventObj.optJSONObject("eventInfo") ?: eventObj.optJSONObject("eventDetails")
                val teamAObj = eventObj.optJSONObject("teamA")
                val teamBObj = eventObj.optJSONObject("teamB")

                val rootCat = eventObj.optString("cat", "")
                val finalCategory = details?.optString("eventCat", "")?.takeIf { it.isNotBlank() }
                    ?: details?.optString("category", "")?.takeIf { it.isNotBlank() }
                    ?: rootCat.takeIf { it.isNotBlank() } ?: "Live Events"

                val matchTitle = details?.optString("eventName", "")?.takeIf { it.isNotBlank() }
                    ?: eventObj.optString("title", "Unknown")

                val linksFieldStr = obj.optString("links", eventObj.optString("links", ""))
                val formatsArray = when {
                    linksFieldStr.startsWith("[") -> {
                        try { JSONArray(linksFieldStr) } catch (_: Exception) { null }
                    }
                    else -> obj.optJSONArray("links") ?: obj.optJSONArray("formats")
                }

                val options = mutableListOf<StreamOption>()
                if (formatsArray != null) {
                    for (f in 0 until formatsArray.length()) {
                        val item = formatsArray.get(f)
                        if (item is JSONObject) {
                            buildStreamOptionWithParams(item, item.optString("name", "Server ${f + 1}"))?.let { options.add(it) }
                        }
                    }
                }

                val actualSlug = if (linksFieldStr.isNotBlank() && !linksFieldStr.startsWith("[")) {
                    linksFieldStr
                } else {
                    obj.optString("slug", "")
                }

                val rawDate = eventObj.optString("date", "").replace("\\/", "/")
                val rawTime = eventObj.optString("time", "")
                val combinedStartTime = if (rawDate.isNotBlank() && rawTime.isNotBlank()) {
                    "$rawDate $rawTime"
                } else {
                    eventObj.optString("startTime", "")
                }

                events.add(
                    LiveSportsEvent(
                        id = obj.optInt("id", i),
                        title = matchTitle,
                        image = details?.optString("eventLogo", "") ?: "",
                        slug = actualSlug,
                        category = finalCategory,
                        startTime = combinedStartTime,
                        endTime = eventObj.optString("end_time", "").replace("\\/", "/"),
                        streamOptions = options,
                        isPublished = isVisible,
                        teamA = teamAObj?.optString("name", "") ?: "",
                        teamB = teamBObj?.optString("name", "") ?: "",
                        logoA = teamAObj?.optString("logo", "") ?: "",
                        logoB = teamBObj?.optString("logo", "") ?: ""
                    )
                )
            }
            events
        } catch (e: Exception) {
            Log.e("SportsEngine", "Live Events Parsing Error", e)
            emptyList()
        }
    }

    suspend fun getStreamLinks(slug: String): List<StreamOption> = withContext(Dispatchers.IO) {
        if (slug.isBlank() || slug == "null") return@withContext emptyList()
        try {
            val decryptedJson = if (slug.startsWith("[")) {
                slug
            } else {
                // Replicate contentOverrideUrl matching for relative paths
                val formattedSlug = if (slug.startsWith("pro/") || slug.startsWith("channels/")) slug else "channels/${slug.lowercase().trim()}.txt"
                fetchData(formattedSlug) ?: return@withContext emptyList()
            }

            val streamUrls = when {
                decryptedJson.startsWith("[") -> JSONArray(decryptedJson)
                decryptedJson.startsWith("{") -> JSONObject(decryptedJson).optJSONArray("streamUrls")
                    ?: JSONObject(decryptedJson).optJSONArray("links")
                else -> null
            } ?: return@withContext emptyList()

            val links = mutableListOf<StreamOption>()
            for (i in 0 until streamUrls.length()) {
                val item = streamUrls.getJSONObject(i)
                buildStreamOptionWithParams(item, item.optString("name", "Server ${i + 1}"))?.let { links.add(it) }
            }
            links
        } catch (e: Exception) {
            Log.e("SportsEngine", "Stream Links Parsing Error", e)
            emptyList()
        }
    }

    private fun buildStreamOptionWithParams(obj: JSONObject, defaultTitle: String): StreamOption? {
        val rawTitle = obj.optString("name", obj.optString("title", "")).trim()
        val displayTitle = if (rawTitle.isNotBlank()) rawTitle else defaultTitle

        var finalUrl = ""
        val urlKeys = listOf("link", "webLink", "url", "streamUrl", "source", "file")
        for (key in urlKeys) {
            val value = obj.optString(key, "").trim()
            if (value.isNotBlank()) {
                finalUrl = value
                break
            }
        }

        val lowerUrl = finalUrl.lowercase()
        if (finalUrl.isBlank() || lowerUrl.contains("tears-of-steel") || lowerUrl.contains("bigbuckbunny")) {
            val tokenApi = obj.optString("tokenApi", "")
            if (tokenApi.isNotBlank()) {
                return StreamOption(title = displayTitle, url = tokenApi)
            }
            return null
        }

        val userAgent = obj.optString("userAgent", obj.optString("user-agent", ""))
        val cookie = obj.optString("cookie", "")
        val referer = obj.optString("referer", obj.optString("Referer", ""))
        val origin = obj.optString("origin", obj.optString("Origin", ""))
        val key = obj.optString("key", obj.optString("drm-key", obj.optString("k", "")))
        val keyId = obj.optString("keyid", obj.optString("kid", obj.optString("drm-keyid", obj.optString("key_id", ""))))
        val licenseUrl = obj.optString("licenseUrl", obj.optString("license_url", ""))

        val params = mutableListOf<String>()
        if (userAgent.isNotBlank()) params.add("user-agent=${Uri.encode(userAgent)}")
        if (referer.isNotBlank()) params.add("referer=${Uri.encode(referer)}")
        if (origin.isNotBlank()) params.add("origin=${Uri.encode(origin)}")
        if (cookie.isNotBlank()) params.add("cookie=${Uri.encode(cookie)}")
        if (key.isNotBlank()) params.add("key=${Uri.encode(key)}")
        if (keyId.isNotBlank()) params.add("keyid=${Uri.encode(keyId)}")
        if (licenseUrl.isNotBlank()) params.add("licenseurl=${Uri.encode(licenseUrl)}")

        val fullUrl = if (params.isNotEmpty() && !finalUrl.contains("|")) {
            "$finalUrl|${params.joinToString("&")}"
        } else {
            finalUrl
        }

        return StreamOption(title = displayTitle, url = fullUrl)
    }
}