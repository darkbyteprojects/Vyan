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
import com.vyan.xtreamplayer.data.managers.AccountStorageManager
import com.vyan.xtreamplayer.data.managers.SettingsManager
import com.vyan.xtreamplayer.data.managers.UserCustomCategory
import com.vyan.xtreamplayer.models.AccountType
import com.vyan.xtreamplayer.models.LiveCategory
import com.vyan.xtreamplayer.models.SeriesItem
import com.vyan.xtreamplayer.models.VodMovie
import com.vyan.xtreamplayer.network.ScrapedPortal
import com.vyan.xtreamplayer.network.XtreamApi
import com.vyan.xtreamplayer.stream.StreamProfile
import com.vyan.xtreamplayer.stream.PlaybackMethodRouter
import com.vyan.xtreamplayer.stream.PlaybackLinkParser
import com.vyan.xtreamplayer.ui.screens.*
import com.vyan.xtreamplayer.ui.theme.XtreamPlayerTheme
import com.vyan.xtreamplayer.utils.AppUpdateDialog
import com.vyan.xtreamplayer.utils.AppUpdateManager
import com.vyan.xtreamplayer.utils.CrashReporter
import com.vyan.xtreamplayer.utils.GithubReleaseInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import com.vyan.xtreamplayer.utils.LocalStreamProxy

data class ActivePlayingStream(
    val profile: StreamProfile,
    val title: String,
    val isLiveStream: Boolean = true
)

class MainActivity : ComponentActivity() {

    private lateinit var accountStorageManager: AccountStorageManager
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.init(this)
        accountStorageManager = AccountStorageManager(this)
        settingsManager = SettingsManager(this)

        LocalStreamProxy.start()

        setContent {
            var refreshTrigger by remember { mutableIntStateOf(0) }
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val packageInfo = remember { context.packageManager.getPackageInfo(context.packageName, 0) }
            val currentAppVersion = packageInfo.versionName ?: "1.0.0"

            var updateInfo by remember { mutableStateOf<GithubReleaseInfo?>(null) }
            var showUpdateDialog by remember { mutableStateOf(false) }

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

                val activeAccount = remember(refreshTrigger) { accountStorageManager.getActiveAccount() }
                val isMoviesHidden = remember(refreshTrigger) { accountStorageManager.isMoviesTabHidden() }
                val isSeriesHidden = remember(refreshTrigger) { accountStorageManager.isSeriesTabHidden() }

                val activeAppMode = remember(refreshTrigger) { sharedPrefs.getString("active_app_mode", if (settingsManager.isLiveTvAutomatedMode) "advanced" else "basic") ?: "basic" }
                val isUnifiedMode = activeAppMode == "unified"
                val isExtremeMode = activeAppMode == "extreme"
                val isAdvancedMode = activeAppMode == "advanced"
                val isSportsMode = activeAppMode == "sports"

                val navBg = Color(0xFF17212B)
                val navSelectedColor = Color(0xFF5288C1)
                val navUnselectedColor = Color(0xFF7F91A4)
                val navTextWhite = Color(0xFFFFFFFF)

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
                                    selectedIconColor = navSelectedColor,
                                    unselectedIconColor = navUnselectedColor,
                                    selectedTextColor = navTextWhite,
                                    unselectedTextColor = navUnselectedColor,
                                    indicatorColor = navBg
                                )

                                NavigationBar(
                                    containerColor = navBg,
                                    contentColor = navTextWhite,
                                    tonalElevation = 0.dp
                                ) {
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
                                            label = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif) },
                                            colors = navColors
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
                                                    val rawProfile = PlaybackLinkParser.parse(payload)
                                                    val routedProfile = PlaybackMethodRouter.decideAndRoute(rawProfile)
                                                    activeStream = ActivePlayingStream(profile = routedProfile, title = title)
                                                }
                                            )
                                        } else if (isUnifiedMode) {
                                            UnifiedLiveTVScreen(
                                                settingsManager = settingsManager,
                                                accountStorageManager = accountStorageManager,
                                                onPlayUnifiedSources = { channelName, sources ->
                                                    if (sources.isNotEmpty()) {
                                                        val topSource = sources.first()
                                                        val rawProfile = PlaybackLinkParser.parse(topSource.streamUrl)
                                                        val routedProfile = PlaybackMethodRouter.decideAndRoute(rawProfile)
                                                        activeStream = ActivePlayingStream(profile = routedProfile, title = channelName)
                                                    }
                                                }
                                            )
                                        } else if (selectedAutoCategory != null) {
                                            AutoChannelsScreen(
                                                channelConfig = selectedAutoCategory!!,
                                                accountStorageManager = accountStorageManager,
                                                settingsManager = settingsManager,
                                                onPlayChannel = { url, title, _ ->
                                                    val rawProfile = PlaybackLinkParser.parse(url)
                                                    val routedProfile = PlaybackMethodRouter.decideAndRoute(rawProfile)
                                                    activeStream = ActivePlayingStream(profile = routedProfile, title = title)
                                                },
                                                onBack = { selectedAutoCategory = null })
                                        } else if (selectedLiveCategory != null && activeAccount != null) {
                                            ChannelsScreen(
                                                categoryName = selectedLiveCategory!!.category_name,
                                                categoryId = selectedLiveCategory!!.category_id,
                                                accountStorageManager = accountStorageManager,
                                                onPlayChannel = { channel ->
                                                    activeAccount.let { acc ->
                                                        val rawUrl = if (acc.type == AccountType.XTREAM) XtreamApi.buildLiveStreamUrl(acc.url, acc.username, acc.pass, channel.stream_id) else channel.direct_source ?: ""
                                                        val rawProfile = PlaybackLinkParser.parse(rawUrl)
                                                        val routedProfile = PlaybackMethodRouter.decideAndRoute(rawProfile)
                                                        activeStream = ActivePlayingStream(profile = routedProfile, title = channel.name)
                                                    }
                                                },
                                                onBack = { selectedLiveCategory = null })
                                        } else if (isBrowsingHiddenCategories && activeAccount != null) {
                                            HiddenCategoriesScreen(
                                                accountStorageManager = accountStorageManager,
                                                onCategoryClick = { selectedLiveCategory = it },
                                                onBack = { isBrowsingHiddenCategories = false })
                                        } else {
                                            LiveTVScreen(
                                                accountStorageManager = accountStorageManager,
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
                                                    val rawProfile = PlaybackLinkParser.parse(extremeChannel.streamUrl)
                                                    rawProfile.headers.putAll(extremeChannel.headers)
                                                    if (extremeChannel.cookie.isNotBlank() && !rawProfile.headers.containsKey("Cookie") && !rawProfile.headers.containsKey("cookie")) {
                                                        rawProfile.headers["Cookie"] = extremeChannel.cookie
                                                    }
                                                    if (extremeChannel.userAgent.isNotBlank()) {
                                                        rawProfile.headers["User-Agent"] = extremeChannel.userAgent
                                                    }
                                                    val routedProfile = PlaybackMethodRouter.decideAndRoute(rawProfile)
                                                    activeStream = ActivePlayingStream(profile = routedProfile, title = extremeChannel.name)
                                                }
                                            )
                                        }
                                    }

                                    "extreme_hub" -> {
                                        ExtremeHubScreen(
                                            settingsManager = settingsManager,
                                            onPlayExtremeChannel = { extremeChannel ->
                                                val rawProfile = PlaybackLinkParser.parse(extremeChannel.streamUrl)
                                                rawProfile.headers.putAll(extremeChannel.headers)
                                                if (extremeChannel.cookie.isNotBlank() && !rawProfile.headers.containsKey("Cookie") && !rawProfile.headers.containsKey("cookie")) {
                                                    rawProfile.headers["Cookie"] = extremeChannel.cookie
                                                }
                                                if (extremeChannel.userAgent.isNotBlank()) {
                                                    rawProfile.headers["User-Agent"] = extremeChannel.userAgent
                                                }
                                                val routedProfile = PlaybackMethodRouter.decideAndRoute(rawProfile)
                                                activeStream = ActivePlayingStream(profile = routedProfile, title = extremeChannel.name)
                                            }
                                        )
                                    }
                                    "settings" -> SettingsScreen(
                                        settingsManager = settingsManager,
                                        accountStorageManager = accountStorageManager,
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
                                                accountStorageManager = accountStorageManager,
                                                settingsManager = settingsManager,
                                                onBack = { previewPortal = null },
                                                onPlayLive = { channel ->
                                                    val rawUrl = XtreamApi.buildLiveStreamUrl(previewPortal!!.url, previewPortal!!.username, previewPortal!!.pass, channel.stream_id)
                                                    val rawProfile = PlaybackLinkParser.parse(rawUrl)
                                                    val routedProfile = PlaybackMethodRouter.decideAndRoute(rawProfile)
                                                    activeStream = ActivePlayingStream(profile = routedProfile, title = channel.name)
                                                },
                                                onPlayMovie = { movie: VodMovie ->
                                                    val rawUrl = XtreamApi.buildMovieStreamUrl(previewPortal!!.url, previewPortal!!.username, previewPortal!!.pass, movie.stream_id, movie.container_extension ?: "mp4")
                                                    val rawProfile = PlaybackLinkParser.parse(rawUrl)
                                                    val routedProfile = PlaybackMethodRouter.decideAndRoute(rawProfile)
                                                    activeStream = ActivePlayingStream(profile = routedProfile, title = movie.name, isLiveStream = false)
                                                },
                                                onPlaySeries = { seriesItem: SeriesItem ->
                                                    val rawProfile = PlaybackLinkParser.parse("")
                                                    val routedProfile = PlaybackMethodRouter.decideAndRoute(rawProfile)
                                                    activeStream = ActivePlayingStream(profile = routedProfile, title = seriesItem.name, isLiveStream = false)
                                                }
                                            )
                                        } else {
                                            DiscoverScreen(
                                                accountStorageManager = accountStorageManager,
                                                onPortalUsed = {
                                                    currentTab = "live_tv"; refreshTrigger++
                                                },
                                                onPreviewPortal = { portal ->
                                                    previewPortal = portal
                                                })
                                        }
                                    }
                                    "movies" -> MoviesScreen(
                                        accountStorageManager = accountStorageManager,
                                        onPlayMovie = { movie ->
                                            if (activeAccount != null) {
                                                val rawUrl = XtreamApi.buildMovieStreamUrl(activeAccount.url, activeAccount.username, activeAccount.pass, movie.stream_id, movie.container_extension ?: "mp4")
                                                val rawProfile = PlaybackLinkParser.parse(rawUrl)
                                                val routedProfile = PlaybackMethodRouter.decideAndRoute(rawProfile)
                                                activeStream = ActivePlayingStream(profile = routedProfile, title = movie.name, isLiveStream = false)
                                            }
                                        },
                                        onLoginClick = { isAddingAccount = true },
                                        onSwitchPlaylistClick = {
                                            showGlobalAccountSwitcher = true
                                        })
                                    "series" -> SeriesScreen(
                                        accountStorageManager = accountStorageManager,
                                        onPlayEpisode = { series, episode ->
                                            if (activeAccount != null) {
                                                val rawUrl = XtreamApi.buildSeriesStreamUrl(activeAccount.url, activeAccount.username, activeAccount.pass, episode.id, episode.container_extension ?: "mp4")
                                                val rawProfile = PlaybackLinkParser.parse(rawUrl)
                                                val routedProfile = PlaybackMethodRouter.decideAndRoute(rawProfile)
                                                activeStream = ActivePlayingStream(profile = routedProfile, title = "${series.name} - E${episode.episode_num}: ${episode.title}", isLiveStream = false)
                                            }
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
                                items(accountStorageManager.getAccounts()) { acc -> Card(onClick = { showGlobalAccountSwitcher = false; accountStorageManager.setActiveAccount(acc.id); refreshTrigger++ }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(0.dp)) { Text(text = acc.alias.ifEmpty { acc.username }, fontWeight = FontWeight.Medium, modifier = Modifier.padding(16.dp)) } }
                            }
                        }, confirmButton = {})
                    }

                    if (isAddingAccount) {
                        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            LoginScreen(
                                accountStorageManager = accountStorageManager,
                                onLoginSuccess = { isAddingAccount = false; refreshTrigger++ },
                                onBack = { isAddingAccount = false })
                        }
                    } else if (activeStream != null) {
                        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                            PlayerScreen(
                                initialProfile = activeStream!!.profile,
                                title = activeStream!!.title,
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