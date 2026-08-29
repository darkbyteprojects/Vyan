package com.vyan.xtreamplayer.network

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class ScrapedPortal(
    val url: String,
    val username: String,
    val pass: String,
    val source: String = "",
    var expiry: String = "Unknown",
    var activeConnections: String = "0",
    var maxConnections: String = "1"
) {
    val key: String get() = "$url|$username|$pass".lowercase()
    val credKey: String get() = "$username|$pass".lowercase()
}

enum class CatalogSource { BEST, WORKS }

data class ScrapePage(
    val portals: List<ScrapedPortal>,
    val nextAfter: String?
) {
    val hasMore: Boolean get() = !nextAfter.isNullOrEmpty()
}

object CatalogScraper {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private const val XML2_BASE_URL = "https://raw.githubusercontent.com/akeotaseo/world_repo/main/Updater_Matrix/XML2/"
    private const val XML2_GITHUB_API = "https://api.github.com/repos/akeotaseo/world_repo/contents/Updater_Matrix/XML2?ref=main"

    private val targetSubs = listOf("IPTV_ZONENEW", "FreeIPTV", "iptvguru", "IPTVfree")
    private const val OAUTH_UA = "PlayTorrio/1.3.6 (by /u/PlayTorrioApp)"
    private val oauthClientIds = listOf("ohXpoqrZYub1kg", "NOe2iKrPPzwscA", "JrPdG8Z6dkWNxA")
    private var oauthToken: String? = null
    private var oauthTokenExpiry: Long = 0L
    private var oauthClientIdx = 0

    private val pasteDomains = listOf("paste.sh", "pastebin.com", "justpaste.it", "controlc.com", "pastes.dev", "text.is", "rentry.co")

    private val base64HttpRegex = """aHR0c[a-zA-Z0-9+/=]{10,}""".toRegex()
    private val rawPasteRegex = """https?://(?:paste\.sh|pastebin\.com|justpaste\.it|controlc\.com|pastes\.dev|text\.is|rentry\.co)/[a-zA-Z0-9#_=-]+""".toRegex(RegexOption.IGNORE_CASE)
    private val urlParamRegex = """(https?://[^?\s"'<]+)\?(?:[^\s"'<]*?&)?(?:username|user)=([^&\s"'<]+)\s*&(?:password|pass)=([^&\s"'<]+)""".toRegex(RegexOption.IGNORE_CASE)
    private val labelRegex = """(?:Portal|Host(?:\s*URL)?|H[ᴏo]s[ᴛt]|Panel|Real|URL|🔗|🌍|🌐)\W*?(https?://[^<\s"']+)[\s\S]{1,500}?(?:Username|Usu[áa]rio|Usuario|User|Us[ᴇe]r|Us[ᴜu][ᴀa]r[ɪi][ᴏo]|👤)\W*?([^\s|<"'\n]+)[\s\S]{1,200}?(?:Password|Senha|Contrase[ñn]a|Pass|P[ᴀa]ss|S[ᴇe]nh[ᴀa]|🔑)\W*?([^\s|<"'\n]+)""".toRegex(RegexOption.IGNORE_CASE)

    // FIXED: Removed the standalone "password" and "username" tokens that were destroying valid credentials
    private val junkTokens = listOf("type=m3u", "output=ts", "password=", "username=")

    private var xml2FilesCache: List<String>? = null
    private var xml2FilesFetchedAt: Long = 0L
    private const val XML2_TTL = 6 * 60 * 60 * 1000L

    suspend fun scrapeCatalogPage(
        source: CatalogSource = CatalogSource.BEST,
        after: String? = null
    ): ScrapePage = withContext(Dispatchers.IO) {
        when (source) {
            CatalogSource.BEST -> {
                var subIdx = 0
                var redditAfter: String? = null

                if (!after.isNullOrEmpty() && after.startsWith("reddit:")) {
                    val parts = after.split(":")
                    if (parts.size >= 3) {
                        subIdx = parts[1].toIntOrNull() ?: 0
                        redditAfter = parts.drop(2).joinToString(":")
                        if (redditAfter.isEmpty() || redditAfter == "null") redditAfter = null
                    } else if (parts.size == 2) {
                        redditAfter = parts[1].takeIf { it.isNotEmpty() && it != "null" }
                    }
                }
                if (subIdx >= targetSubs.size) subIdx = 0

                scrapeRedditCatalog(subIdx, redditAfter)
            }
            CatalogSource.WORKS -> {
                val files = getXml2Files()
                val idx = if (after != null && after.startsWith("xml2:")) {
                    after.substring(5).toIntOrNull() ?: 0
                } else 0

                if (idx < files.size) scrapeXml2File(idx, files)
                else ScrapePage(emptyList(), null)
            }
        }
    }

    private suspend fun scrapeRedditCatalog(subIdx: Int, after: String?): ScrapePage {
        val currentSub = targetSubs[subIdx]
        val extractedPortals = mutableMapOf<String, ScrapedPortal>()
        val maxResults = 50

        val posts = fetchRedditOAuth(currentSub, after) ?: fetchRedditRss(currentSub, after)

        if (posts == null) {
            return if (subIdx + 1 < targetSubs.size) ScrapePage(emptyList(), "reddit:${subIdx + 1}:")
            else ScrapePage(emptyList(), null)
        }

        val rawPosts = posts.first
        val nextAfterRaw = posts.second

        for (post in rawPosts) {
            if (extractedPortals.size >= maxResults) break

            extractDirectPortals(post, extractedPortals, "Reddit", maxResults)

            val deepLinks = mutableListOf<String>()
            base64HttpRegex.findAll(post).forEach { match ->
                try {
                    val decoded = String(Base64.decode(match.value, Base64.DEFAULT), Charsets.UTF_8)
                    if (decoded.startsWith("http") && pasteDomains.any { decoded.contains(it) }) deepLinks.add(decoded)
                    else if (!decoded.startsWith("http") && decoded.contains(":")) extractDirectPortals(decoded, extractedPortals, "Decoded Base64", maxResults)
                } catch (_: Exception) {}
            }
            rawPasteRegex.findAll(post).forEach { deepLinks.add(it.value) }

            deepLinks.distinct().take(4).forEach { dl ->
                if (extractedPortals.size >= maxResults) return@forEach
                val pasteText = fetchPasteText(dl)
                if (!pasteText.isNullOrEmpty()) extractDirectPortals(pasteText, extractedPortals, "Paste Link", maxResults)
            }
        }

        val nextCursor = if (!nextAfterRaw.isNullOrEmpty() && nextAfterRaw != "null") {
            "reddit:$subIdx:$nextAfterRaw"
        } else if (subIdx + 1 < targetSubs.size) {
            "reddit:${subIdx + 1}:"
        } else null

        return ScrapePage(extractedPortals.values.toList(), nextCursor)
    }

    private suspend fun getOAuthToken(): String? {
        if (oauthToken != null && System.currentTimeMillis() < oauthTokenExpiry) return oauthToken
        for (i in oauthClientIds.indices) {
            val idx = (oauthClientIdx + i) % oauthClientIds.size
            val clientId = oauthClientIds[idx]
            try {
                val authHeader = "Basic " + Base64.encodeToString("$clientId:".toByteArray(), Base64.NO_WRAP)
                val body = FormBody.Builder().add("grant_type", "https://oauth.reddit.com/grants/installed_client").add("device_id", "DO_NOT_TRACK_THIS_DEVICE").build()
                val request = Request.Builder().url("https://www.reddit.com/api/v1/access_token").post(body).header("User-Agent", OAUTH_UA).header("Authorization", authHeader).build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val data = JSONObject(response.body!!.string())
                    oauthToken = data.optString("access_token")
                    oauthTokenExpiry = System.currentTimeMillis() + ((data.optInt("expires_in", 3600) - 60) * 1000L)
                    oauthClientIdx = idx
                    return oauthToken
                }
            } catch (e: Exception) {}
        }
        oauthClientIdx = (oauthClientIdx + 1) % oauthClientIds.size
        return null
    }

    private suspend fun fetchRedditOAuth(sub: String, after: String?): Pair<List<String>, String?>? {
        val token = getOAuthToken() ?: return null
        val url = "https://oauth.reddit.com/r/$sub/new?limit=100&raw_json=1" + if (!after.isNullOrEmpty()) "&after=$after" else ""
        val request = Request.Builder().url(url).header("User-Agent", OAUTH_UA).header("Authorization", "Bearer $token").build()

        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                if (response.code == 401 || response.code == 403) { oauthToken = null; oauthTokenExpiry = 0L }
                return null
            }
            val dataObj = JSONObject(response.body!!.string()).getJSONObject("data")
            val children = dataObj.getJSONArray("children")

            val nextAfterRaw = dataObj.optString("after", "")
            val nextAfter = nextAfterRaw.takeIf { it.isNotEmpty() && it != "null" }

            val posts = mutableListOf<String>()

            for (i in 0 until children.length()) {
                val post = children.getJSONObject(i).getJSONObject("data")
                val title = decodeXmlEntities(post.optString("title", ""))
                val text = decodeXmlEntities(post.optString("selftext", ""))
                posts.add("$title $text")
            }
            Pair(posts, nextAfter)
        } catch (e: Exception) { null }
    }

    private fun fetchRedditRss(sub: String, after: String?): Pair<List<String>, String?>? {
        val url = "https://www.reddit.com/r/$sub/new/.rss?limit=25" + if (!after.isNullOrEmpty()) "&after=$after" else ""
        val request = Request.Builder().url(url).header("User-Agent", OAUTH_UA).build()

        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null
            val body = response.body!!.string()

            val entryRegex = """<entry>(.*?)</entry>""".toRegex(setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            val titleRegex = """<title[^>]*>(.*?)</title>""".toRegex(setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            val contentRegex = """<content[^>]*>(.*?)</content>""".toRegex(setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            val idRegex = """<id>(t3_[^<]+)</id>""".toRegex(RegexOption.IGNORE_CASE)

            val posts = mutableListOf<String>()
            var lastId: String? = null

            entryRegex.findAll(body).forEach { match ->
                val entry = match.groupValues[1]
                val title = decodeXmlEntities(titleRegex.find(entry)?.groupValues?.get(1) ?: "")
                val content = decodeXmlEntities(contentRegex.find(entry)?.groupValues?.get(1) ?: "")
                lastId = idRegex.find(entry)?.groupValues?.get(1)
                posts.add("$title $content")
            }
            Pair(posts, if (posts.size >= 20) lastId else null)
        } catch (e: Exception) { null }
    }

    private suspend fun getXml2Files(): List<String> {
        if (xml2FilesCache != null && (System.currentTimeMillis() - xml2FilesFetchedAt) < XML2_TTL) {
            return xml2FilesCache!!
        }
        try {
            val request = Request.Builder().url(XML2_GITHUB_API).header("User-Agent", OAUTH_UA).header("Accept", "application/vnd.github+json").build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val array = JSONArray(response.body!!.string())
                val entries = mutableListOf<Pair<String, Int>>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    if (obj.optString("type") == "file") {
                        val name = obj.optString("name")
                        if (name.lowercase().endsWith(".txt")) {
                            entries.add(Pair(name, obj.optInt("size", Int.MAX_VALUE)))
                        }
                    }
                }
                if (entries.isNotEmpty()) {
                    val sorted = entries.sortedBy { it.second }.map { Uri.encode(it.first) }
                    xml2FilesCache = sorted
                    xml2FilesFetchedAt = System.currentTimeMillis()
                    return sorted
                }
            }
        } catch (e: Exception) {}

        val fallback = listOf("25.txt", "71.txt", "ABN.txt", "DOV.txt", "br.txt", "channels_fulltime.txt", "kgen.txt", "x.txt", "%7BAllTelegram%7D2.txt")
        xml2FilesCache = fallback
        xml2FilesFetchedAt = System.currentTimeMillis()
        return fallback
    }

    private suspend fun scrapeXml2File(idx: Int, files: List<String>): ScrapePage {
        val fileName = files[idx]
        val pretty = Uri.decode(fileName).replace(".txt", "")
        val maxResults = 50

        val extractedPortals = mutableMapOf<String, ScrapedPortal>()
        try {
            val response = client.newCall(Request.Builder().url("$XML2_BASE_URL$fileName").build()).execute()
            val body = response.body?.string()
            if (!body.isNullOrEmpty()) extractDirectPortals(body, extractedPortals, "XML2/$pretty", maxResults)
        } catch (e: Exception) {}

        val nextCursor = if (idx + 1 < files.size) "xml2:${idx + 1}" else null
        return ScrapePage(extractedPortals.values.toList(), nextCursor)
    }

    private fun extractDirectPortals(rawText: String, accMap: MutableMap<String, ScrapedPortal>, sourceName: String, max: Int) {
        if (accMap.size >= max || rawText.length < 15 || isJunkCode(rawText)) return

        val cleaned = rawText
            .replace("&amp;", "&").replace("&quot;", "\"")
            .replace(Regex("<(?:p|br|div|li|h\\d)[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")

        urlParamRegex.findAll(cleaned).forEach { finalizePortal(accMap, it.groupValues[1], it.groupValues[2], it.groupValues[3], sourceName, max) }
        labelRegex.findAll(cleaned).forEach { finalizePortal(accMap, it.groupValues[1], it.groupValues[2], it.groupValues[3], sourceName, max) }
    }

    private fun isJunkCode(text: String): Boolean {
        val markers = listOf("Array.isArray", "prototype.", "function(", "var ", "const ", "let ", "return!", "void ", ".message}", "window.", "document.")
        var hits = 0
        for (m in markers) {
            if (text.contains(m)) hits++
            if (hits >= 2) return true
        }
        return false
    }

    private fun finalizePortal(accMap: MutableMap<String, ScrapedPortal>, rawUrl: String, rawUser: String, rawPass: String, sourceName: String, max: Int) {
        if (accMap.size >= max) return

        var url = rawUrl.replace(Regex("\\s+"), "")
        val qIdx = url.indexOf('?')
        if (qIdx >= 0) url = url.substring(0, qIdx)
        if (url.contains("@")) url = "http://${url.substring(url.lastIndexOf('@') + 1)}"

        while (url.endsWith("/")) url = url.substring(0, url.length - 1)

        // FIXED: Expanded regex to catch get.php and other extensions safely
        url = url.replace(Regex("/(?:get\\.php|live\\.php|portal\\.php|c|index\\.php|playlist\\.php|player_api\\.php|xmltv\\.php|get|live|portal|index|playlist|player_api|xmltv)$", RegexOption.IGNORE_CASE), "")

        while (url.endsWith("/")) url = url.substring(0, url.length - 1)
        if (!url.startsWith("http")) url = "http://$url"

        var user = rawUser
        while (user.startsWith("=")) user = user.substring(1)
        user = user.split(Regex("[ \n&?]")).firstOrNull()?.trim() ?: ""

        var pass = rawPass
        while (pass.startsWith("=")) pass = pass.substring(1)
        pass = pass.split(Regex("[ \n&?]")).firstOrNull()?.trim() ?: ""

        if (url.isEmpty() || user.length < 3 || pass.length < 3) return
        if (user.contains("http") || pass.contains("http")) return

        val lu = user.lowercase()
        val lp = pass.lowercase()
        for (j in junkTokens) {
            if (lu.contains(j) || lp.contains(j)) return
        }

        val key = "$url|$user|$pass".lowercase()
        if (!accMap.containsKey(key)) {
            accMap[key] = ScrapedPortal(url, user, pass, sourceName)
        }
    }

    private suspend fun fetchPasteText(url: String): String? {
        if (url.contains("paste.sh/") && url.contains("#")) {
            return decryptPasteSh(url)
        }
        val cleanUrl = when {
            url.contains("pastebin.com/") && !url.contains("/raw/") -> "https://pastebin.com/raw/${url.substringAfterLast("/")}"
            url.contains("pastes.dev/") -> "https://api.pastes.dev/${url.substringAfterLast("/")}"
            url.contains("rentry.co/") && !url.contains("/raw") -> "https://rentry.co/${url.substringAfterLast("/")}/raw"
            else -> url
        }
        return try { client.newCall(Request.Builder().url(cleanUrl).header("User-Agent", OAUTH_UA).build()).execute().body?.string() } catch (e: Exception) { null }
    }

    private fun decryptPasteSh(urlWithHash: String): String? {
        val hashIdx = urlWithHash.indexOf('#')
        if (hashIdx <= 0) return null
        val baseUrl = urlWithHash.substring(0, hashIdx)
        val clientKey = urlWithHash.substring(hashIdx + 1)
        val id = baseUrl.substringAfterLast('/')

        try {
            val response = client.newCall(Request.Builder().url("$baseUrl.txt").header("User-Agent", OAUTH_UA).build()).execute()
            val raw = response.body?.string() ?: return null
            val lines = raw.split("\n")
            if (lines.isEmpty()) return null

            val serverKey = lines.first().trim()
            val b64 = lines.drop(1).joinToString("").trim()
            val cipherBytes = Base64.decode(b64, Base64.DEFAULT)
            if (cipherBytes.size < 17) return null

            val salt = cipherBytes.copyOfRange(8, 16)
            val ct = cipherBytes.copyOfRange(16, cipherBytes.size)
            val password = "$id$serverKey$clientKey" + "https://paste.sh"
            val passBytes = password.toByteArray(Charsets.UTF_8)

            try {
                val spec = PBEKeySpec(password.toCharArray(), salt, 1, 48 * 8)
                val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
                val keyIv = factory.generateSecret(spec).encoded
                val key = keyIv.copyOfRange(0, 32)
                val iv = keyIv.copyOfRange(32, 48)

                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                return String(cipher.doFinal(ct), Charsets.UTF_8)
            } catch (e: Exception) {}

            try {
                val pair = evpBytesToKey(passBytes, salt, 32, 16)
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(pair.first, "AES"), IvParameterSpec(pair.second))
                return String(cipher.doFinal(ct), Charsets.UTF_8)
            } catch (e: Exception) {}

        } catch (e: Exception) {}
        return null
    }

    private fun evpBytesToKey(password: ByteArray, salt: ByteArray, keyLen: Int, ivLen: Int): Pair<ByteArray, ByteArray> {
        val md5 = MessageDigest.getInstance("MD5")
        val targetLen = keyLen + ivLen
        var derived = ByteArray(0)
        var block = ByteArray(0)
        while (derived.size < targetLen) {
            md5.update(block)
            md5.update(password)
            md5.update(salt)
            block = md5.digest()
            val newDerived = ByteArray(derived.size + block.size)
            System.arraycopy(derived, 0, newDerived, 0, derived.size)
            System.arraycopy(block, 0, newDerived, derived.size, block.size)
            derived = newDerived
        }
        return Pair(derived.copyOfRange(0, keyLen), derived.copyOfRange(keyLen, targetLen))
    }

    private fun decodeXmlEntities(s: String): String {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&#32;", " ")
    }
}

object IptvVerifier {
    // 1. TrustManager for HTTPS bypass (Bypass bad SSL certs exactly like Dart does)
    private val trustAllCerts = arrayOf<TrustManager>(
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
    )

    private val sslContext = SSLContext.getInstance("SSL").apply {
        init(null, trustAllCerts, SecureRandom())
    }

    // 2. Configure OkHttpClient to mirror Dart's leniency and follow redirects
    private val verifyClient = OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
        .hostnameVerifier { _, _ -> true }
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun verifyUntil(
        portals: List<ScrapedPortal>,
        target: Int,
        onAttempted: suspend (ScrapedPortal) -> Unit,
        onProgress: suspend (Int, Int, Int) -> Unit,
        onAlive: suspend (ScrapedPortal) -> Unit
    ) = coroutineScope {
        if (portals.isEmpty()) return@coroutineScope
        val nextIdx = AtomicInteger(0)
        var checked = 0
        val aliveCount = AtomicInteger(0)
        var stopped = false
        val mutex = Mutex()

        val workers = List(4) {
            launch(Dispatchers.IO) {
                while (!stopped) {
                    if (aliveCount.get() >= target) {
                        stopped = true
                        break
                    }
                    val idx = nextIdx.getAndIncrement()
                    if (idx >= portals.size) break

                    val p = portals[idx]
                    withContext(Dispatchers.Main) { onAttempted(p) }

                    val verified = verifyPortalLogin(p)

                    if (stopped) break

                    mutex.withLock {
                        checked++
                        if (verified != null && aliveCount.get() < target) {
                            aliveCount.incrementAndGet()
                            withContext(Dispatchers.Main) { onAlive(verified) }
                        }
                        withContext(Dispatchers.Main) { onProgress(checked, portals.size, aliveCount.get()) }
                        if (aliveCount.get() >= target) stopped = true
                    }
                }
            }
        }
        workers.forEach { it.join() }
    }

    private suspend fun verifyPortalLogin(portal: ScrapedPortal): ScrapedPortal? = withContext(
        Dispatchers.IO) {
        try {
            // Encode safely to match Dart's Uri.encodeComponent
            val encUser = URLEncoder.encode(portal.username, "UTF-8")
            val encPass = URLEncoder.encode(portal.pass, "UTF-8")
            val urlStr = "${portal.url}/player_api.php?username=$encUser&password=$encPass"

            val request = Request.Builder()
                .url(urlStr)
                .header("User-Agent", "IPTVSmartersPro")
                .header("Accept", "application/json,*/*")
                .build()

            // .use { } GUARANTEES background thread execution completes and avoids freezing
            verifyClient.newCall(request).execute().use { response ->

                // EXACT DART BEHAVIOR: Read body even if response code is 401/403/500
                val bodyStr = response.body?.string()?.trim()?.removePrefix("\uFEFF") ?: return@withContext null

                // Safely handle messy JSON Arrays or Objects without crashing like Retrofit
                val root = if (bodyStr.startsWith("[")) {
                    JSONArray(bodyStr).optJSONObject(0) ?: JSONObject()
                } else {
                    JSONObject(bodyStr)
                }

                val info = root.optJSONObject("user_info")

                // If there is no user_info at all, it's definitely dead
                if (info == null) return@withContext null

                val auth = info.optString("auth", "")
                val status = info.optString("status", "").lowercase()

                // CORRECTED LOGIC: Only strictly true if auth is 1 or status is active
                val isOk = auth == "1" || status == "active"

                if (isOk) {
                    val exp = info.optString("exp_date", "")
                    portal.expiry = if (exp.isNotEmpty() && exp != "null") exp else "Unknown"

                    val activeCons = info.optString("active_cons", "")
                    portal.activeConnections = if (activeCons.isNotEmpty() && activeCons != "null") activeCons else "0"

                    val maxCons = info.optString("max_connections", "")
                    portal.maxConnections = if (maxCons.isNotEmpty() && maxCons != "null") maxCons else "1"

                    return@withContext portal
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}