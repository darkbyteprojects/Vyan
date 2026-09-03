package com.vyan.xtreamplayer

import android.content.Context
import android.os.Bundle
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.*
import com.vyan.xtreamplayer.data.managers.AccountManager
import com.vyan.xtreamplayer.data.managers.SettingsManager
import com.vyan.xtreamplayer.data.managers.UserCustomCategory
import com.vyan.xtreamplayer.models.AccountType
import com.vyan.xtreamplayer.models.LiveCategory
import com.vyan.xtreamplayer.models.LiveChannel
import com.vyan.xtreamplayer.models.SeriesItem
import com.vyan.xtreamplayer.models.VodMovie
import com.vyan.xtreamplayer.network.ScrapedPortal
import com.vyan.xtreamplayer.network.XtreamApi
import com.vyan.xtreamplayer.ui.screens.*
import com.vyan.xtreamplayer.ui.theme.XtreamPlayerTheme
import com.vyan.xtreamplayer.utils.AppUpdateDialog
import com.vyan.xtreamplayer.utils.AppUpdateManager
import com.vyan.xtreamplayer.utils.CrashReporter
import com.vyan.xtreamplayer.utils.ExtremeSyncWorker
import com.vyan.xtreamplayer.utils.GithubReleaseInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import com.vyan.xtreamplayer.utils.LocalStreamProxy

data class ActivePlayingStream(
    val url: String,
    val title: String,
    val sources: List<AggregatedChannel> = emptyList(),
    val unifiedSources: List<UnifiedSource> = emptyList(),
    val userAgent: String = "",
    val cookie: String = "",
    val keyId: String = "",
    val key: String = "",
    val headers: Map<String, String> = emptyMap(),
    // FIX BUG 4: State added for live/VOD routing
    val isLiveStream: Boolean = true
)
class MainActivity : ComponentActivity() {

    private lateinit var accountManager: AccountManager
    private lateinit var settingsManager: SettingsManager
    private var backPressedTime: Long = 0L

    private fun verifyIndianRegionSilently(context: Context, sharedPrefs: android.content.SharedPreferences) {
        if (sharedPrefs.getBoolean("is_verified_indian_network", false)) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val simCountry = tm.simCountryIso?.lowercase()
                val networkCountry = tm.networkCountryIso?.lowercase()

                if (simCountry == "in" || networkCountry == "in") {
                    sharedPrefs.edit().putBoolean("is_verified_indian_network", true).apply()
                    return@launch
                }

                val client = OkHttpClient()
                val request = Request.Builder().url("https://ipapi.co/json/").build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        if (body.contains("\"country_code\": \"IN\"") || body.contains("\"country_code\":\"IN\"")) {
                            sharedPrefs.edit().putBoolean("is_verified_indian_network", true).apply()
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun scheduleExtremeSyncWork(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<ExtremeSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "ExtremeSyncWorkJob",
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.init(this)
        accountManager = AccountManager(this)
        settingsManager = SettingsManager(this)

        // START THE INVISIBLE LOCAL SERVER
        LocalStreamProxy.start()

        scheduleExtremeSyncWork(this)

        setContent {
            var refreshTrigger by remember { mutableIntStateOf(0) }
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val packageInfo = remember { context.packageManager.getPackageInfo(context.packageName, 0) }
            val currentAppVersion = packageInfo.versionName ?: "1.0.0"

            var updateInfo by remember { mutableStateOf<GithubReleaseInfo?>(null) }
            var showUpdateDialog by remember { mutableStateOf(false) }

            // Automatic In-App Update Verification
            LaunchedEffect(Unit) {
                scope.launch {
                    try {
                        val release = AppUpdateManager.checkGithubForUpdate(currentAppVersion)
                        if (release != null) {
                            updateInfo = release
                            delay(1000)
                            showUpdateDialog = true
                        }
                    } catch (_: Exception) {}
                }
            }

            XtreamPlayerTheme {
                val saveableStateHolder = rememberSaveableStateHolder()
                val sharedPrefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }

                LaunchedEffect(Unit) { verifyIndianRegionSilently(context, sharedPrefs) }

                var currentTab by remember { mutableStateOf("live_tv") }
                var isAddingAccount by remember { mutableStateOf(false) }
                var isBrowsingHiddenCategories by remember { mutableStateOf(false) }
                var showGlobalAccountSwitcher by remember { mutableStateOf(false) }

                var selectedLiveCategory by remember { mutableStateOf<LiveCategory?>(null) }
                var selectedAutoCategory by remember { mutableStateOf<UserCustomCategory?>(null) }
                var activeStream by remember { mutableStateOf<ActivePlayingStream?>(null) }
                var previewPortal by remember { mutableStateOf<ScrapedPortal?>(null) }

                val activeAccount = remember(refreshTrigger) { accountManager.getActiveAccount() }
                val isMoviesHidden = remember(refreshTrigger) { accountManager.isMoviesTabHidden() }
                val isSeriesHidden = remember(refreshTrigger) { accountManager.isSeriesTabHidden() }

                val activeAppMode = remember(refreshTrigger) { sharedPrefs.getString("active_app_mode", if (settingsManager.isLiveTvAutomatedMode) "advanced" else "basic") ?: "basic" }
                val isUnifiedMode = activeAppMode == "unified"
                val isExtremeMode = activeAppMode == "extreme"
                val isAdvancedMode = activeAppMode == "advanced"
                val isSportsMode = activeAppMode == "sports"

                BackHandler(enabled = true) {
                    when {
                        activeStream != null -> activeStream = null
                        previewPortal != null -> previewPortal = null
                        selectedAutoCategory != null -> selectedAutoCategory = null
                        selectedLiveCategory != null -> selectedLiveCategory = null
                        isBrowsingHiddenCategories -> isBrowsingHiddenCategories = false
                        isAddingAccount -> isAddingAccount = false
                        currentTab != "live_tv" -> currentTab = "live_tv"
                        else -> {
                            if (System.currentTimeMillis() - backPressedTime < 2000) finish()
                            else {
                                backPressedTime = System.currentTimeMillis()
                                Toast.makeText(context, "Press BACK again to exit", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    Scaffold(
                        bottomBar = {
                            if (selectedLiveCategory == null && selectedAutoCategory == null && !isBrowsingHiddenCategories && previewPortal == null) {
                                val navColors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color(0xFF09090B),
                                    unselectedIconColor = Color(0xFFA1A1AA),
                                    selectedTextColor = Color(0xFFFAFAFA),
                                    unselectedTextColor = Color(0xFFA1A1AA),
                                    indicatorColor = Color(0xFFFAFAFA)
                                )

                                NavigationBar(containerColor = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onSurface, tonalElevation = 0.dp) {
                                    val navItems = remember(isMoviesHidden, isSeriesHidden, activeAppMode) {
                                        listOfNotNull(
                                            Triple("live_tv", "Live TV", Icons.Default.Tv),
                                            if (!isMoviesHidden) Triple("movies", "Movies", Icons.Default.Movie) else null,
                                            if (!isSeriesHidden) Triple("series", "Series", Icons.Default.VideoLibrary) else null,
                                            if (isAdvancedMode && !isUnifiedMode) Triple("discover", "Discover", Icons.Default.Explore) else null,
                                            if (isExtremeMode && !isUnifiedMode) Triple("extreme_hub", "Extreme", Icons.Default.Bolt) else null,
                                            Triple("settings", "Settings", Icons.Default.Settings)
                                        )
                                    }

                                    navItems.forEach { (id, label, icon) ->
                                        NavigationBarItem(
                                            selected = currentTab == id,
                                            onClick = { currentTab = id; selectedLiveCategory = null; selectedAutoCategory = null; previewPortal = null; isBrowsingHiddenCategories = false },
                                            icon = { Icon(icon, label) },
                                            label = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.SansSerif) },
                                            colors = if (id == "extreme_hub") NavigationBarItemDefaults.colors(selectedIconColor = Color(0xFF09090B), unselectedIconColor = Color(0xFFA1A1AA), selectedTextColor = Color(0xFFE50914), unselectedTextColor = Color(0xFFA1A1AA), indicatorColor = Color(0xFFE50914)) else navColors
                                        )
                                    }
                                }
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.background
                    ) { innerPadding ->
                        Box(modifier = Modifier.fillMaxSize().padding(innerPadding).background(MaterialTheme.colorScheme.background)) {
                            saveableStateHolder.SaveableStateProvider(currentTab) {
                                when (currentTab) {
                                    "live_tv" -> {
                                        if (isSportsMode) {
                                            SportsScreen(
                                                onPlayMatch = { payload, title ->
                                                    activeStream = ActivePlayingStream(
                                                        url = payload,
                                                        title = title
                                                    )
                                                }
                                            )
                                        } else if (isUnifiedMode) {
                                            UnifiedLiveTVScreen(
                                                settingsManager = settingsManager,
                                                accountManager = accountManager,
                                                onPlayUnifiedSources = { channelName, sources ->
                                                    if (sources.isNotEmpty()) {
                                                        val topSource = sources.first()
                                                        activeStream = ActivePlayingStream(
                                                            url = topSource.streamUrl,
                                                            title = channelName,
                                                            unifiedSources = sources,
                                                            userAgent = topSource.userAgent,
                                                            cookie = topSource.cookie,
                                                            keyId = topSource.keyId,
                                                            key = topSource.key,
                                                            headers = topSource.headers
                                                        )
                                                    }
                                                }
                                            )
                                        } else if (selectedAutoCategory != null) {
                                            AutoChannelsScreen(
                                                channelConfig = selectedAutoCategory!!,
                                                accountManager = accountManager,
                                                settingsManager = settingsManager,
                                                onPlayChannel = { url, title, sources ->
                                                    activeStream = ActivePlayingStream(
                                                        url = url,
                                                        title = title,
                                                        sources = sources
                                                    )
                                                },
                                                onBack = { selectedAutoCategory = null })
                                        } else if (selectedLiveCategory != null && activeAccount != null) {
                                            ChannelsScreen(
                                                categoryName = selectedLiveCategory!!.category_name,
                                                categoryId = selectedLiveCategory!!.category_id,
                                                accountManager = accountManager,
                                                onPlayChannel = { channel ->
                                                    activeAccount.let { acc ->
                                                        activeStream = ActivePlayingStream(
                                                            url = if (acc.type == AccountType.XTREAM) XtreamApi.buildLiveStreamUrl(
                                                                acc.url,
                                                                acc.username,
                                                                acc.pass,
                                                                channel.stream_id
                                                            ) else channel.direct_source ?: "",
                                                            title = channel.name
                                                        )
                                                    }
                                                },
                                                onBack = { selectedLiveCategory = null })
                                        } else if (isBrowsingHiddenCategories && activeAccount != null) {
                                            HiddenCategoriesScreen(
                                                accountManager = accountManager,
                                                onCategoryClick = { selectedLiveCategory = it },
                                                onBack = { isBrowsingHiddenCategories = false })
                                        } else {
                                            LiveTVScreen(
                                                accountManager = accountManager,
                                                settingsManager = settingsManager,
                                                onCategoryClick = { selectedLiveCategory = it },
                                                onAutoCategoryClick = { selectedAutoCategory = it },
                                                onOpenArchivedFolder = {
                                                    isBrowsingHiddenCategories = true
                                                },
                                                onLoginClick = { isAddingAccount = true },
                                                onSwitchPlaylistClick = {
                                                    showGlobalAccountSwitcher = true
                                                },
                                                onPlayExtremeChannel = { extremeChannel ->
                                                    val headersMap = mutableMapOf<String, String>()
                                                    headersMap.putAll(extremeChannel.headers)
                                                    if (extremeChannel.cookie.isNotBlank() && !headersMap.containsKey("Cookie") && !headersMap.containsKey("cookie")) {
                                                        headersMap["Cookie"] = extremeChannel.cookie
                                                    }

                                                    val unifiedSource = UnifiedSource(
                                                        sourceName = "Extreme: ${extremeChannel.sourceName}",
                                                        originalChannelName = extremeChannel.name,
                                                        streamUrl = extremeChannel.streamUrl,
                                                        sourceType = "EXTREME",
                                                        userAgent = extremeChannel.userAgent,
                                                        cookie = extremeChannel.cookie,
                                                        keyId = extremeChannel.keyId,
                                                        key = extremeChannel.key,
                                                        headers = headersMap
                                                    )

                                                    activeStream = ActivePlayingStream(
                                                        url = extremeChannel.streamUrl,
                                                        title = extremeChannel.name,
                                                        unifiedSources = listOf(unifiedSource),
                                                        userAgent = extremeChannel.userAgent,
                                                        cookie = extremeChannel.cookie,
                                                        keyId = extremeChannel.keyId,
                                                        key = extremeChannel.key,
                                                        headers = headersMap
                                                    )
                                                }
                                            )
                                        }
                                    }

                                    "extreme_hub" -> {
                                        ExtremeHubScreen(
                                            settingsManager = settingsManager,
                                            onPlayExtremeChannel = { extremeChannel ->
                                                val headersMap = mutableMapOf<String, String>()
                                                headersMap.putAll(extremeChannel.headers)
                                                if (extremeChannel.cookie.isNotBlank() && !headersMap.containsKey("Cookie") && !headersMap.containsKey("cookie")) {
                                                    headersMap["Cookie"] = extremeChannel.cookie
                                                }

                                                val unifiedSource = UnifiedSource(
                                                    sourceName = "Extreme: ${extremeChannel.sourceName}",
                                                    originalChannelName = extremeChannel.name,
                                                    streamUrl = extremeChannel.streamUrl,
                                                    sourceType = "EXTREME",
                                                    userAgent = extremeChannel.userAgent,
                                                    cookie = extremeChannel.cookie,
                                                    keyId = extremeChannel.keyId,
                                                    key = extremeChannel.key,
                                                    headers = headersMap
                                                )

                                                activeStream = ActivePlayingStream(
                                                    url = extremeChannel.streamUrl,
                                                    title = extremeChannel.name,
                                                    unifiedSources = listOf(unifiedSource),
                                                    userAgent = extremeChannel.userAgent,
                                                    cookie = extremeChannel.cookie,
                                                    keyId = extremeChannel.keyId,
                                                    key = extremeChannel.key,
                                                    headers = headersMap
                                                )
                                            }
                                        )
                                    }
                                    "settings" -> SettingsScreen(
                                        settingsManager = settingsManager,
                                        accountManager = accountManager,
                                        onAddAccountClick = { isAddingAccount = true },
                                        onAccountSwitched = {
                                            refreshTrigger++; selectedLiveCategory =
                                            null; selectedAutoCategory = null; previewPortal =
                                            null; isBrowsingHiddenCategories = false
                                        })
                                    "discover" -> {
                                        if (previewPortal != null) {
                                            PortalPreviewScreen(
                                                portal = previewPortal!!,
                                                accountManager = accountManager,
                                                settingsManager = settingsManager,
                                                onBack = { previewPortal = null },
                                                onPlayLive = { channel ->
                                                    activeStream = ActivePlayingStream(
                                                        url = XtreamApi.buildLiveStreamUrl(
                                                            previewPortal!!.url,
                                                            previewPortal!!.username,
                                                            previewPortal!!.pass,
                                                            channel.stream_id
                                                        ), title = channel.name
                                                    )
                                                },
                                                onPlayMovie = { movie: VodMovie ->
                                                    activeStream = ActivePlayingStream(
                                                        url = XtreamApi.buildMovieStreamUrl(
                                                            previewPortal!!.url,
                                                            previewPortal!!.username,
                                                            previewPortal!!.pass,
                                                            movie.stream_id,
                                                            movie.container_extension ?: "mp4"
                                                        ),
                                                        title = movie.name,
                                                        isLiveStream = false
                                                    )
                                                },
                                                onPlaySeries = { seriesItem: SeriesItem ->
                                                    activeStream = ActivePlayingStream(
                                                        url = "",
                                                        title = seriesItem.name,
                                                        isLiveStream = false
                                                    )
                                                }
                                            )
                                        } else {
                                            DiscoverScreen(
                                                accountManager = accountManager,
                                                onPortalUsed = {
                                                    currentTab = "live_tv"; refreshTrigger++
                                                },
                                                onPreviewPortal = { portal ->
                                                    previewPortal = portal
                                                })
                                        }
                                    }
                                    "movies" -> MoviesScreen(
                                        accountManager = accountManager,
                                        onPlayMovie = { movie ->
                                            if (activeAccount != null) activeStream =
                                                ActivePlayingStream(
                                                    url = XtreamApi.buildMovieStreamUrl(
                                                        activeAccount.url,
                                                        activeAccount.username,
                                                        activeAccount.pass,
                                                        movie.stream_id,
                                                        movie.container_extension ?: "mp4"
                                                    ),
                                                    title = movie.name,
                                                    isLiveStream = false // Set as VOD
                                                )
                                        },
                                        onLoginClick = { isAddingAccount = true },
                                        onSwitchPlaylistClick = {
                                            showGlobalAccountSwitcher = true
                                        })
                                    "series" -> SeriesScreen(
                                        accountManager = accountManager,
                                        onPlayEpisode = { series, episode ->
                                            if (activeAccount != null) activeStream =
                                                ActivePlayingStream(
                                                    url = XtreamApi.buildSeriesStreamUrl(
                                                        activeAccount.url,
                                                        activeAccount.username,
                                                        activeAccount.pass,
                                                        episode.id,
                                                        episode.container_extension ?: "mp4"
                                                    ),
                                                    title = "${series.name} - E${episode.episode_num}: ${episode.title}",
                                                    isLiveStream = false // Set as VOD
                                                )
                                        },
                                        onLoginClick = { isAddingAccount = true },
                                        onSwitchPlaylistClick = {
                                            showGlobalAccountSwitcher = true
                                        })
                                }
                            }
                        }
                    }

                    if (showGlobalAccountSwitcher) {
                        AlertDialog(containerColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp), onDismissRequest = { showGlobalAccountSwitcher = false }, title = { Text("Switch Playlist", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp) }, text = {
                            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(accountManager.getAccounts()) { acc -> Card(onClick = { showGlobalAccountSwitcher = false; accountManager.setActiveAccount(acc.id); refreshTrigger++ }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(0.dp)) { Text(text = acc.alias.ifEmpty { acc.username }, fontWeight = FontWeight.Medium, modifier = Modifier.padding(16.dp)) } }
                            }
                        }, confirmButton = {})
                    }

                    if (isAddingAccount) {
                        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            LoginScreen(
                                accountManager = accountManager,
                                onLoginSuccess = { isAddingAccount = false; refreshTrigger++ },
                                onBack = { isAddingAccount = false })
                        }
                    } else if (activeStream != null) {
                        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                            PlayerScreen(
                                streamUrl = activeStream!!.url,
                                title = activeStream!!.title,
                                sources = activeStream!!.sources,
                                unifiedSources = activeStream!!.unifiedSources,
                                userAgent = activeStream!!.userAgent,
                                cookie = activeStream!!.cookie,
                                keyId = activeStream!!.keyId,
                                key = activeStream!!.key,
                                headers = activeStream!!.headers,
                                settingsManager = settingsManager,
                                isLiveStream = activeStream!!.isLiveStream, // Parameter supplied correctly
                                onBack = { activeStream = null }
                            )
                        }
                    }

                    if (showUpdateDialog && updateInfo != null) {
                        AppUpdateDialog(
                            updateInfo = updateInfo,
                            onDismiss = { showUpdateDialog = false }
                        )
                    }
                }
            }
        }
    }
}