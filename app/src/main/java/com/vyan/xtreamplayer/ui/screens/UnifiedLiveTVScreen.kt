@file:OptIn(ExperimentalMaterial3Api::class)

package com.vyan.xtreamplayer.ui.screens

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vyan.xtreamplayer.core.ExtremeSourceConfig
import com.vyan.xtreamplayer.core.ExtremeSourceRegistry
import com.vyan.xtreamplayer.core.SourceType
import com.vyan.xtreamplayer.data.managers.AccountManager
import com.vyan.xtreamplayer.data.managers.DataCache
import com.vyan.xtreamplayer.data.managers.SettingsManager
import com.vyan.xtreamplayer.models.AccountType
import com.vyan.xtreamplayer.network.ScrapedPortal
import com.vyan.xtreamplayer.network.XtreamApi
import com.vyan.xtreamplayer.utils.M3uParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

data class UnifiedSource(
    val sourceName: String,
    val originalChannelName: String,
    val streamUrl: String,
    val sourceType: String,
    val userAgent: String = "",
    val cookie: String = "",
    val keyId: String = "",
    val key: String = "",
    val headers: Map<String, String> = emptyMap()
)

data class UnifiedChannelDef(
    val name: String,
    val keywords: List<String>
)

data class UnifiedProviderDef(
    val id: String,
    val name: String,
    val channels: List<UnifiedChannelDef>
)

private val nonAlnumRegex = Regex("[^a-z0-9]")
private val whitespaceRegex = Regex("\\s+")

fun matchesChannel(channelName: String, keywords: List<String>): Boolean {
    val cleanChannel = nonAlnumRegex.replace(channelName.lowercase(), " ")
    val channelWords = whitespaceRegex.split(cleanChannel).filter { it.isNotBlank() }

    if (channelWords.isEmpty()) return false

    return keywords.any { kw ->
        val cleanKw = nonAlnumRegex.replace(kw.lowercase(), " ")
        val kwWords = whitespaceRegex.split(cleanKw).filter { it.isNotBlank() }

        if (kwWords.isEmpty()) return@any false

        if (kwWords.size > 1) {
            channelWords.windowed(kwWords.size).any { window -> window == kwWords }
        } else {
            channelWords.contains(kwWords[0])
        }
    }
}

// -------------------------------------------------------------------------
// UNIFIED SEARCH MANAGER
// -------------------------------------------------------------------------
object UnifiedSearchManager {
    val activeSources = mutableStateListOf<UnifiedSource>()
    var isSearching by mutableStateOf(false)

    private val searchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentSearchJob: Job? = null
    private val httpClient = OkHttpClient()

    fun startBackgroundSearch(
        context: Context,
        channelDef: UnifiedChannelDef,
        accountManager: AccountManager,
        onFirstSourceFound: () -> Unit,
        onNoSourcesFound: () -> Unit
    ) {
        currentSearchJob?.cancel()
        activeSources.clear()
        isSearching = true
        var hasLaunchedPlayer = false

        currentSearchJob = searchScope.launch {
            try {
                // FIX: If Unified Mode is opened first, force load the Extreme Registry config
                if (ExtremeSourceRegistry.ALL_SOURCES.isEmpty()) {
                    ExtremeSourceRegistry.loadMasterSources(context)
                }

                val appPrefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

                // EXTREME PREFS
                val unifiedDisabledExtreme = appPrefs.getStringSet("unified_disabled_extreme_sources", emptySet()) ?: emptySet()
                val activeExtremeSources = appPrefs.getStringSet("selected_extreme_sources", null) ?: ExtremeSourceRegistry.ALL_SOURCES.map { it.id }.toSet()

                // PLAYLIST PREFS
                val explicitlyDisabledPlaylists = appPrefs.getStringSet("unified_disabled_playlists", emptySet()) ?: emptySet()
                val explicitlySelectedPlaylists = appPrefs.getStringSet("unified_selected_playlists", null)
                    ?: appPrefs.getStringSet("unified_enabled_playlists", null)

                // PORTAL PREFS
                val unifiedUsePortals = appPrefs.getBoolean("unified_use_discovered_portals", true)

                // Load custom extreme sources
                val customJsonStr = appPrefs.getString("custom_extreme_sources", "[]") ?: "[]"
                val customConfigsList = mutableListOf<ExtremeSourceConfig>()
                try {
                    val arr = JSONArray(customJsonStr)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        customConfigsList.add(
                            ExtremeSourceConfig(
                                id = obj.optString("id", "custom_${System.currentTimeMillis()}"),
                                name = obj.optString("name", "Custom Source"),
                                category = obj.optString("category", "General"),
                                url = obj.optString("url", ""),
                                type = if (obj.optString("type", "M3U") == "M3U") SourceType.M3U_DIRECT else SourceType.JSON_WRAPPED
                            )
                        )
                    }
                } catch (_: Exception) {}

                val allExtremeConfigs = ExtremeSourceRegistry.ALL_SOURCES + customConfigsList

                val listLock = Any()
                fun addSourceSync(src: UnifiedSource) {
                    synchronized(listLock) {
                        if (activeSources.none { it.streamUrl == src.streamUrl }) {
                            activeSources.add(src)
                            if (!hasLaunchedPlayer) {
                                hasLaunchedPlayer = true
                                launch(Dispatchers.Main) { onFirstSourceFound() }
                            }
                        }
                    }
                }

                // FIX: Retrieve custom concurrency limit (default 1 to completely eliminate lag)
                val concurrencyLimit = appPrefs.getInt("unified_search_concurrency_limit", 1)
                val searchConcurrencyLimit = Semaphore(concurrencyLimit)

                coroutineScope {

                    // 1. EXTREME SOURCES
                    val configsToSearch = allExtremeConfigs.filter { src ->
                        val isCustom = src.id.startsWith("custom_")
                        (isCustom || activeExtremeSources.contains(src.id)) && !unifiedDisabledExtreme.contains(src.id)
                    }

                    configsToSearch.map { config ->
                        async {
                            if (!isActive) return@async
                            searchConcurrencyLimit.withPermit {
                                val channels = try {
                                    ExtremeHubAggregator.cachedChannels[config.id]
                                        ?: ExtremeSourceRegistry.fetchChannels(config).also {
                                            if (it.isNotEmpty()) ExtremeHubAggregator.cachedChannels[config.id] = it
                                        }
                                } catch (_: Exception) { emptyList() }

                                for (extCh in channels) {
                                    if (!isActive) break
                                    if (matchesChannel(extCh.name, channelDef.keywords)) {
                                        val headersMap = extCh.headers.toMutableMap()
                                        if (extCh.cookie.isNotBlank() && !headersMap.keys.any { it.equals("Cookie", ignoreCase = true) }) {
                                            headersMap["Cookie"] = extCh.cookie
                                        }

                                        addSourceSync(UnifiedSource(
                                            sourceName = "Extreme: ${extCh.sourceName}",
                                            originalChannelName = extCh.name,
                                            streamUrl = extCh.streamUrl,
                                            sourceType = "EXTREME",
                                            userAgent = extCh.userAgent,
                                            cookie = extCh.cookie,
                                            keyId = extCh.keyId,
                                            key = extCh.key,
                                            headers = headersMap
                                        ))
                                    }
                                }
                            }
                        }
                    }

                    // 2. DISCOVERED PORTALS
                    if (unifiedUsePortals && isActive) {
                        val prefs = context.getSharedPreferences("DiscoverPrefs", Context.MODE_PRIVATE)
                        val savedJson = prefs.getString("saved_portals", null)
                        if (savedJson != null) {
                            try {
                                val scrapedPortals: List<ScrapedPortal> = Gson().fromJson(savedJson, object : TypeToken<List<ScrapedPortal>>() {}.type)
                                scrapedPortals.map { portal ->
                                    async {
                                        if (!isActive) return@async
                                        searchConcurrencyLimit.withPermit {
                                            try {
                                                val res = XtreamApi.service.getLiveStreams(XtreamApi.formatApiUrl(portal.url), portal.username, portal.pass, null)
                                                if (res.isSuccessful) {
                                                    res.body()?.forEach { ch ->
                                                        if (matchesChannel(ch.name, channelDef.keywords)) {
                                                            val streamUrl = XtreamApi.buildLiveStreamUrl(portal.url, portal.username, portal.pass, ch.stream_id)
                                                            addSourceSync(UnifiedSource(
                                                                sourceName = "Portal: ${portal.username}",
                                                                originalChannelName = ch.name,
                                                                streamUrl = streamUrl,
                                                                sourceType = "PORTAL"
                                                            ))
                                                        }
                                                    }
                                                }
                                            } catch (_: Exception) {}
                                        }
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    // 3. USER PLAYLISTS (Xtream & M3U)
                    accountManager.getAccounts().map { acc ->
                        async {
                            if (!isActive) return@async
                            if (explicitlyDisabledPlaylists.contains(acc.id)) return@async
                            if (explicitlySelectedPlaylists != null && !explicitlySelectedPlaylists.contains(acc.id)) return@async

                            searchConcurrencyLimit.withPermit {
                                if (acc.type == AccountType.XTREAM) {
                                    val cached = DataCache.liveChannels.filterKeys { it.startsWith(acc.id) }.values.flatten()
                                    val channelsToSearch = if (cached.isNotEmpty()) {
                                        cached.map { it.name to it.stream_id }
                                    } else {
                                        try {
                                            val res = XtreamApi.service.getLiveStreams(XtreamApi.formatApiUrl(acc.url), acc.username, acc.pass, null)
                                            if (res.isSuccessful) res.body()?.map { it.name to it.stream_id } ?: emptyList() else emptyList()
                                        } catch (_: Exception) { emptyList() }
                                    }

                                    for ((chName, streamId) in channelsToSearch) {
                                        if (!isActive) break
                                        if (matchesChannel(chName, channelDef.keywords)) {
                                            val streamUrl = XtreamApi.buildLiveStreamUrl(acc.url, acc.username, acc.pass, streamId)
                                            addSourceSync(UnifiedSource(
                                                sourceName = "Playlist: ${acc.alias.ifEmpty { acc.username }}",
                                                originalChannelName = chName,
                                                streamUrl = streamUrl,
                                                sourceType = "IPTV"
                                            ))
                                        }
                                    }
                                } else {
                                    try {
                                        val content = if (acc.type == AccountType.M3U_URL) {
                                            httpClient.newCall(Request.Builder().url(acc.url).build()).execute().body?.string() ?: ""
                                        } else {
                                            context.contentResolver.openInputStream(Uri.parse(acc.localFilePath))?.bufferedReader()?.use { it.readText() } ?: ""
                                        }
                                        val parsedChannels = M3uParser.parse(content)
                                        for (m3uCh in parsedChannels) {
                                            if (!isActive) break
                                            if (matchesChannel(m3uCh.name, channelDef.keywords)) {
                                                addSourceSync(UnifiedSource(
                                                    sourceName = "Playlist: ${acc.alias.ifEmpty { acc.username }}",
                                                    originalChannelName = m3uCh.name,
                                                    streamUrl = m3uCh.url,
                                                    sourceType = "IPTV"
                                                ))
                                            }
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                    }
                }.awaitAll()

                withContext(Dispatchers.Main) {
                    isSearching = false
                    if (!hasLaunchedPlayer) {
                        onNoSourcesFound()
                    }
                }
            } catch (_: CancellationException) {
                withContext(Dispatchers.Main) { isSearching = false }
            }
        }
    }

    fun stopSearch() {
        currentSearchJob?.cancel()
        isSearching = false
    }
}

object UnifiedConfig {
    val gradients = listOf(
        listOf(Color(0xFFE50914), Color(0xFFB20710)),
        listOf(Color(0xFF00E5FF), Color(0xFF00838F)),
        listOf(Color(0xFF8A2BE2), Color(0xFF4A148C)),
        listOf(Color(0xFFFF9800), Color(0xFFE65100)),
        listOf(Color(0xFF4CAF50), Color(0xFF1B5E20)),
        listOf(Color(0xFFE91E63), Color(0xFF880E4F))
    )

    val providers = listOf(
        UnifiedProviderDef(
            id = "sony",
            name = "Sony Network",
            channels = listOf(
                UnifiedChannelDef("Sony SAB", listOf("sony sab", "sab hd", "sab tv", "sab")),
                UnifiedChannelDef("Sony SET", listOf("sony set", "set hd", "sony entertainment", "sony tv")),
                UnifiedChannelDef("Sony MAX", listOf("sony max", "max hd", "sony max 2")),
                UnifiedChannelDef("Sony PIX", listOf("sony pix", "pix hd")),
                UnifiedChannelDef("Sony Aath", listOf("sony aath", "sonyaath")),
                UnifiedChannelDef("Sony Wah", listOf("sony wah", "sonywah")),
                UnifiedChannelDef("Sony Pal", listOf("sony pal", "sonypal")),
                UnifiedChannelDef("Sony BBC Earth", listOf("sony bbc earth", "bbc earth hd")),
                UnifiedChannelDef("Sony YAY!", listOf("sony yay", "yay")),
                UnifiedChannelDef("Sony Sports 1", listOf("sony sports 1", "ten 1", "sony sports ten 1", "sony six")),
                UnifiedChannelDef("Sony Sports 2", listOf("sony sports 2", "ten 2", "sony sports ten 2")),
                UnifiedChannelDef("Sony Sports 3", listOf("sony sports 3", "ten 3", "sony sports ten 3")),
                UnifiedChannelDef("Sony Sports 4", listOf("sony sports 4", "ten 4", "sony sports ten 4")),
                UnifiedChannelDef("Sony Sports 5", listOf("sony sports 5", "ten 5", "sony sports ten 5"))
            )
        ),
        UnifiedProviderDef(
            id = "star",
            name = "Star & Disney Network",
            channels = listOf(
                UnifiedChannelDef("Star Plus", listOf("star plus", "starplus")),
                UnifiedChannelDef("Star Gold", listOf("star gold", "stargold", "star gold 2")),
                UnifiedChannelDef("Star Gold Select", listOf("star gold select", "gold select")),
                UnifiedChannelDef("Star Gold Thrills", listOf("star gold thrills", "gold thrills")),
                UnifiedChannelDef("Star Gold Romance", listOf("star gold romance", "gold romance")),
                UnifiedChannelDef("Star Bharat", listOf("star bharat", "starbharat")),
                UnifiedChannelDef("Star Utsav", listOf("star utsav", "starutsav")),
                UnifiedChannelDef("Star Movies", listOf("star movies", "starmovies")),
                UnifiedChannelDef("Star World", listOf("star world", "starworld")),
                UnifiedChannelDef("Star Sports 1", listOf("star sports 1", "sports 1 hd")),
                UnifiedChannelDef("Star Sports 2", listOf("star sports 2", "sports 2 hd")),
                UnifiedChannelDef("Star Sports Hindi 1", listOf("star sports hindi", "star sports 1 hindi")),
                UnifiedChannelDef("Star Sports Select 1", listOf("star sports select 1", "select 1 hd")),
                UnifiedChannelDef("Star Sports Select 2", listOf("star sports select 2", "select 2 hd")),
                UnifiedChannelDef("Star Sports First", listOf("star sports first")),
                UnifiedChannelDef("Asianet", listOf("asianet", "asianet hd", "asianet movies")),
                UnifiedChannelDef("Asianet Plus", listOf("asianet plus")),
                UnifiedChannelDef("Star Maa", listOf("star maa", "maa tv", "maa hd", "maa movies", "maa music")),
                UnifiedChannelDef("Star Vijay", listOf("star vijay", "vijay tv", "vijay hd", "vijay super", "vijay takkar")),
                UnifiedChannelDef("Star Pravah", listOf("star pravah", "pravah hd")),
                UnifiedChannelDef("Star Jalsha", listOf("star jalsha", "jalsha hd", "jalsha movies"))
            )
        ),
        UnifiedProviderDef(
            id = "zee",
            name = "Zee Network",
            channels = listOf(
                UnifiedChannelDef("Zee TV", listOf("zee tv", "zeetv")),
                UnifiedChannelDef("Zee Cinema", listOf("zee cinema", "zeecinema")),
                UnifiedChannelDef("Zee News", listOf("zee news", "zeenews")),
                UnifiedChannelDef("Zee Anmol", listOf("zee anmol", "zeeanmol", "zee anmol cinema")),
                UnifiedChannelDef("Zee Action", listOf("zee action")),
                UnifiedChannelDef("Zee Bollywood", listOf("zee bollywood")),
                UnifiedChannelDef("Zee Classic", listOf("zee classic")),
                UnifiedChannelDef("Zee Cafe", listOf("zee cafe", "zeecafe")),
                UnifiedChannelDef("Zee Zest", listOf("zee zest")),
                UnifiedChannelDef("Zee Business", listOf("zee business")),
                UnifiedChannelDef("&pictures", listOf("&pictures", "andpictures")),
                UnifiedChannelDef("&tv", listOf("&tv", "andtv")),
                UnifiedChannelDef("&flix", listOf("&flix", "andflix")),
                UnifiedChannelDef("&prive hd", listOf("&prive hd", "andprive")),
                UnifiedChannelDef("&xplor", listOf("&xplor", "andxplor")),
                UnifiedChannelDef("Zee Telugu", listOf("zee telugu", "zee cinemalu")),
                UnifiedChannelDef("Zee Tamil", listOf("zee tamil", "zee thirai")),
                UnifiedChannelDef("Zee Kannada", listOf("zee kannada")),
                UnifiedChannelDef("Zee Keralam", listOf("zee keralam")),
                UnifiedChannelDef("Zee Marathi", listOf("zee marathi", "zee talkies", "zee 24 taas")),
                UnifiedChannelDef("Zee Bangla", listOf("zee bangla", "zee 24 ghanta")),
                UnifiedChannelDef("Zee Punjabi", listOf("zee punjabi")),
                UnifiedChannelDef("Zee Odia", listOf("zee odia")),
                UnifiedChannelDef("Zee Biskope", listOf("zee biskope", "zeebiskope")),
                UnifiedChannelDef("Zee 24 Kalak", listOf("zee 24 kalak", "zee kalak")),
                UnifiedChannelDef("Zee UP UK", listOf("zee up uk")),
                UnifiedChannelDef("Zee MP CG", listOf("zee mp cg")),
                UnifiedChannelDef("Zee Rajasthan", listOf("zee rajasthan"))
            )
        ),
        UnifiedProviderDef(
            id = "colors",
            name = "Colors & Viacom18 Network",
            channels = listOf(
                UnifiedChannelDef("Colors TV", listOf("colors tv", "colors hd", "colors")),
                UnifiedChannelDef("Colors Cineplex", listOf("colors cineplex", "cineplex hd", "cineplex bollywood", "colors cineplex superhits")),
                UnifiedChannelDef("Colors Rishtey", listOf("colors rishtey", "rishtey")),
                UnifiedChannelDef("Colors Infinity", listOf("colors infinity")),
                UnifiedChannelDef("MTV India", listOf("mtv india", "mtv hd", "mtv", "mtv beats")),
                UnifiedChannelDef("VH1", listOf("vh1", "vh1 india")),
                UnifiedChannelDef("Sports18 1", listOf("sports18 1", "sports 18 1", "sports18-1")),
                UnifiedChannelDef("Sports18 Khel", listOf("sports18 khel", "sports 18 khel")),
                UnifiedChannelDef("Comedy Central", listOf("comedy central")),
                UnifiedChannelDef("Colors Kannada", listOf("colors kannada", "colors super", "colors kannada cinema")),
                UnifiedChannelDef("Colors Marathi", listOf("colors marathi")),
                UnifiedChannelDef("Colors Bangla", listOf("colors bangla", "colors bangla cinema")),
                UnifiedChannelDef("Colors Gujarati", listOf("colors gujarati")),
                UnifiedChannelDef("Colors Tamil", listOf("colors tamil")),
                UnifiedChannelDef("Colors Odia", listOf("colors odia"))
            )
        ),
        UnifiedProviderDef(
            id = "sun",
            name = "Sun Network",
            channels = listOf(
                UnifiedChannelDef("Sun TV", listOf("sun tv", "suntv", "sun tv hd")),
                UnifiedChannelDef("KTV", listOf("ktv", "ktv hd")),
                UnifiedChannelDef("Sun Music", listOf("sun music", "sunmusic")),
                UnifiedChannelDef("Sun News", listOf("sun news")),
                UnifiedChannelDef("Sun Life", listOf("sun life")),
                UnifiedChannelDef("Sun Bangla", listOf("sun bangla")),
                UnifiedChannelDef("Sun Marathi", listOf("sun marathi")),
                UnifiedChannelDef("Aditya TV", listOf("aditya tv")),
                UnifiedChannelDef("Gemini TV", listOf("gemini tv", "geminitv", "gemini movies", "gemini music", "gemini comedy", "gemini life")),
                UnifiedChannelDef("Udaya TV", listOf("udaya tv", "udayatv", "udaya movies", "udaya comedy")),
                UnifiedChannelDef("Surya TV", listOf("surya tv", "suryatv", "surya movies", "surya comedy"))
            )
        ),
        UnifiedProviderDef(
            id = "discovery_warner",
            name = "Discovery & Kids Network",
            channels = listOf(
                UnifiedChannelDef("Discovery Channel", listOf("discovery channel", "discovery")),
                UnifiedChannelDef("Animal Planet", listOf("animal planet", "animalplanet")),
                UnifiedChannelDef("TLC", listOf("tlc", "tlc hd")),
                UnifiedChannelDef("Discovery Science", listOf("discovery science")),
                UnifiedChannelDef("Discovery Turbo", listOf("discovery turbo")),
                UnifiedChannelDef("Investigation Discovery", listOf("id channel", "investigation discovery")),
                UnifiedChannelDef("Cartoon Network", listOf("cartoon network", "cartoonnetwork")),
                UnifiedChannelDef("Pogo", listOf("pogo", "pogo tv")),
                UnifiedChannelDef("Nick / Nickelodeon", listOf("nick", "nickelodeon", "sonic", "nick jr")),
                UnifiedChannelDef("Hungama TV", listOf("hungama", "hungama tv", "super hungama")),
                UnifiedChannelDef("Disney Channel", listOf("disney channel", "disney junior"))
            )
        )
    )
}

@Composable
fun UnifiedLiveTVScreen(
    settingsManager: SettingsManager,
    accountManager: AccountManager,
    onPlayUnifiedSources: (channelName: String, sources: List<UnifiedSource>) -> Unit
) {
    var selectedProvider by remember { mutableStateOf<UnifiedProviderDef?>(null) }
    val premiumBg = Color(0xFF09090B)
    val premiumSurface = Color(0xFF18181B)
    val premiumAccent = Color(0xFFFAFAFA)
    val premiumTextSec = Color(0xFFA1A1AA)

    BackHandler(enabled = selectedProvider != null) {
        selectedProvider = null
    }

    if (selectedProvider != null) {
        UnifiedCategoryScreen(
            provider = selectedProvider!!,
            accountManager = accountManager,
            onPlayUnifiedSources = onPlayUnifiedSources,
            onBack = { selectedProvider = null }
        )
    } else {
        Box(modifier = Modifier.fillMaxSize().background(premiumBg).statusBarsPadding()) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Unified Mode", color = premiumAccent, fontSize = 32.sp, fontWeight = FontWeight.Black)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Portals, Playlists & DRM Streams Merged", color = premiumTextSec, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 40.dp)
                ) {
                    items(UnifiedConfig.providers.size) { index ->
                        val provider = UnifiedConfig.providers[index]
                        val gradient = UnifiedConfig.gradients[index % UnifiedConfig.gradients.size]

                        Card(
                            onClick = { selectedProvider = provider },
                            modifier = Modifier.fillMaxWidth().height(130.dp),
                            colors = CardDefaults.cardColors(containerColor = premiumSurface),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Box(modifier = Modifier.fillMaxWidth().height(6.dp).background(Brush.horizontalGradient(gradient)))
                                Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        Icon(Icons.Default.FolderSpecial, null, tint = premiumAccent, modifier = Modifier.size(32.dp))
                                    }
                                    Column {
                                        Text(provider.name, color = premiumAccent, fontSize = 18.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("${provider.channels.size} Channels", color = premiumTextSec, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UnifiedCategoryScreen(
    provider: UnifiedProviderDef,
    accountManager: AccountManager,
    onPlayUnifiedSources: (channelName: String, sources: List<UnifiedSource>) -> Unit,
    onBack: () -> Unit
) {
    val premiumBg = Color(0xFF09090B)
    val premiumSurface = Color(0xFF18181B)
    val premiumAccent = Color(0xFFFAFAFA)
    val premiumTextSec = Color(0xFFA1A1AA)
    val context = LocalContext.current

    var searchQuery by remember { mutableStateOf("") }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingChannelName by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        onDispose {
            UnifiedSearchManager.stopSearch()
        }
    }

    BackHandler(enabled = isSearchExpanded || isLoading) {
        if (isLoading) {
            UnifiedSearchManager.stopSearch()
            isLoading = false
        } else {
            isSearchExpanded = false
            searchQuery = ""
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(premiumBg).statusBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp).animateContentSize()) {
                if (isSearchExpanded) {
                    TextField(
                        value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search channels...", color = premiumTextSec, fontSize = 15.sp) },
                        leadingIcon = { IconButton(onClick = { isSearchExpanded = false; searchQuery = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = premiumAccent) } },
                        trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, null, tint = premiumAccent) } },
                        shape = CircleShape, singleLine = true,
                        colors = TextFieldDefaults.colors(focusedContainerColor = premiumSurface, unfocusedContainerColor = premiumSurface, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedTextColor = premiumAccent, unfocusedTextColor = premiumAccent)
                    )
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { UnifiedSearchManager.stopSearch(); onBack() }, modifier = Modifier.clip(CircleShape).background(premiumSurface).size(42.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = premiumAccent)
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(provider.name, color = premiumAccent, fontSize = 24.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Select a channel to play instantly", color = premiumTextSec, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        IconButton(onClick = { isSearchExpanded = true }, modifier = Modifier.clip(CircleShape).background(premiumSurface).size(42.dp)) {
                            Icon(Icons.Default.Search, "Search", tint = premiumAccent)
                        }
                    }
                }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                items(provider.channels) { channelDef ->
                    if (searchQuery.isBlank() || channelDef.name.contains(searchQuery, ignoreCase = true)) {
                        Card(
                            onClick = {
                                isLoading = true
                                loadingChannelName = channelDef.name
                                UnifiedSearchManager.startBackgroundSearch(
                                    context = context,
                                    channelDef = channelDef,
                                    accountManager = accountManager,
                                    onFirstSourceFound = {
                                        isLoading = false
                                        onPlayUnifiedSources(channelDef.name, UnifiedSearchManager.activeSources)
                                    },
                                    onNoSourcesFound = {
                                        isLoading = false
                                        Toast.makeText(context, "No matching sources found for ${channelDef.name}", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            },
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = premiumSurface),
                            elevation = CardDefaults.cardElevation(0.dp)
                        ) {
                            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF27272A)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Tv, contentDescription = "Channel", tint = premiumAccent)
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = channelDef.name, color = premiumAccent, fontSize = 17.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(text = "Tap to play instantly", fontSize = 13.sp, color = premiumTextSec, fontWeight = FontWeight.SemiBold)
                                }
                                Icon(Icons.Default.PlayCircleFilled, null, tint = premiumAccent, modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                }
            }
        }

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.8f))
                    .pointerInput(Unit) {},
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.width(280.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = premiumSurface)
                ) {
                    Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = premiumAccent, modifier = Modifier.size(48.dp), strokeWidth = 4.dp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(loadingChannelName, color = premiumAccent, fontSize = 18.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Searching all sources...", color = Color(0xFFFF9800), fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}