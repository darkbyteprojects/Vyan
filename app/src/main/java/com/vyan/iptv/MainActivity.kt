package com.vyan.iptv

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vyan.iptv.data.managers.AccountStorageManager
import com.vyan.iptv.data.managers.SettingsManager
import com.vyan.iptv.models.AccountType
import com.vyan.iptv.models.LiveCategory
import com.vyan.iptv.network.XtreamApi
import com.vyan.iptv.stream.PlaybackLinkParser
import com.vyan.iptv.stream.PlaybackMethodRouter
import com.vyan.iptv.stream.StreamProfile
import com.vyan.iptv.ui.screens.*
import com.vyan.iptv.ui.theme.XtreamPlayerTheme
import com.vyan.iptv.utils.CrashReporter

data class ActivePlayingStream(
    val profile: StreamProfile,
    val title: String,
    val isLiveStream: Boolean = true
)

class MainActivity : ComponentActivity() {

    private lateinit var accountStorageManager: AccountStorageManager
    private lateinit var settingsManager: SettingsManager
    private var backPressedTime: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.init(this)
        accountStorageManager = AccountStorageManager(this)
        settingsManager = SettingsManager(this)

        setContent {
            var refreshTrigger by remember { mutableIntStateOf(0) }
            val context = LocalContext.current

            XtreamPlayerTheme {
                val saveableStateHolder = rememberSaveableStateHolder()

                var currentTab by remember { mutableStateOf("live_tv") }
                var isAddingAccount by remember { mutableStateOf(false) }
                var isBrowsingHiddenCategories by remember { mutableStateOf(false) }

                // Category holding variables mapped to LiveCategory
                var selectedLiveCategory by remember { mutableStateOf<LiveCategory?>(null) }

                var activeStream by remember { mutableStateOf<ActivePlayingStream?>(null) }
                val activeAccount = remember(refreshTrigger) { accountStorageManager.getActiveAccount() }

                val navBg = Color(0xFF17212B)
                val navSelectedColor = Color(0xFF5288C1)
                val navUnselectedColor = Color(0xFF7F91A4)
                val navTextWhite = Color(0xFFFFFFFF)

                BackHandler(enabled = true) {
                    when {
                        activeStream != null -> activeStream = null
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
                            if (selectedLiveCategory == null && !isBrowsingHiddenCategories && !isAddingAccount && activeStream == null) {
                                NavigationBar(
                                    containerColor = navBg,
                                    contentColor = navTextWhite,
                                    tonalElevation = 0.dp
                                ) {
                                    val isMoviesHidden = remember(refreshTrigger) { accountStorageManager.isMoviesTabHidden() }
                                    val isSeriesHidden = remember(refreshTrigger) { accountStorageManager.isSeriesTabHidden() }

                                    val navItems = remember(isMoviesHidden, isSeriesHidden) {
                                        listOfNotNull(
                                            Triple("live_tv", "Live TV", Icons.Default.Tv),
                                            if (!isMoviesHidden) Triple("movies", "Movies", Icons.Default.Movie) else null,
                                            if (!isSeriesHidden) Triple("series", "Series", Icons.Default.VideoLibrary) else null,
                                            Triple("settings", "Settings", Icons.Default.Settings)
                                        )
                                    }

                                    navItems.forEach { (id, label, icon) ->
                                        NavigationBarItem(
                                            selected = currentTab == id,
                                            onClick = {
                                                currentTab = id
                                                selectedLiveCategory = null
                                                isBrowsingHiddenCategories = false
                                            },
                                            icon = { Icon(icon, label) },
                                            label = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                            colors = NavigationBarItemDefaults.colors(
                                                selectedIconColor = navSelectedColor,
                                                unselectedIconColor = navUnselectedColor,
                                                selectedTextColor = navTextWhite,
                                                unselectedTextColor = navUnselectedColor,
                                                indicatorColor = navBg
                                            )
                                        )
                                    }
                                }
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.background
                    ) { innerPadding ->
                        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                            saveableStateHolder.SaveableStateProvider(currentTab) {
                                when (currentTab) {
                                    "live_tv" -> {
                                        if (selectedLiveCategory != null && activeAccount != null) {
                                            ChannelsScreen(
                                                categoryName = selectedLiveCategory!!.category_name,
                                                categoryId = selectedLiveCategory!!.category_id,
                                                accountStorageManager = accountStorageManager,
                                                onPlayChannel = { channel ->
                                                    val rawUrl = if (activeAccount.type == AccountType.XTREAM) {
                                                        XtreamApi.buildLiveStreamUrl(activeAccount.url, activeAccount.username, activeAccount.pass, channel.stream_id)
                                                    } else {
                                                        channel.direct_source ?: ""
                                                    }
                                                    val rawProfile = PlaybackLinkParser.parse(rawUrl)
                                                    val routedProfile = PlaybackMethodRouter.decideAndRoute(rawProfile)
                                                    activeStream = ActivePlayingStream(profile = routedProfile, title = channel.name)
                                                },
                                                onBack = { selectedLiveCategory = null }
                                            )
                                        } else if (isBrowsingHiddenCategories && activeAccount != null) {
                                            HiddenCategoriesScreen(
                                                accountStorageManager = accountStorageManager,
                                                onCategoryClick = { category ->
                                                    selectedLiveCategory = category
                                                },
                                                onBack = { isBrowsingHiddenCategories = false }
                                            )
                                        } else {
                                            LiveTVScreen(
                                                accountStorageManager = accountStorageManager,
                                                settingsManager = settingsManager,
                                                onCategoryClick = { category ->
                                                    selectedLiveCategory = category
                                                },

                                                onOpenArchivedFolder = { isBrowsingHiddenCategories = true },
                                                onLoginClick = { isAddingAccount = true },
                                                onSwitchPlaylistClick = { }
                                            )
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
                                        onSwitchPlaylistClick = { }
                                    )
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
                                        onSwitchPlaylistClick = { }
                                    )
                                    "settings" -> SettingsScreen(
                                        settingsManager = settingsManager,
                                        accountStorageManager = accountStorageManager,
                                        onAddAccountClick = { isAddingAccount = true },
                                        onAccountSwitched = {
                                            refreshTrigger++
                                            selectedLiveCategory = null
                                            isBrowsingHiddenCategories = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (isAddingAccount) {
                        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            LoginScreen(
                                accountStorageManager = accountStorageManager,
                                onLoginSuccess = { isAddingAccount = false; refreshTrigger++ },
                                onBack = { isAddingAccount = false }
                            )
                        }
                    } else if (activeStream != null) {
                        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                            PlayerScreen(
                                initialProfile = activeStream!!.profile,
                                title = activeStream!!.title,
                                isLiveStream = activeStream!!.isLiveStream,
                                settingsManager = settingsManager,
                                onBack = { activeStream = null }
                            )
                        }
                    }
                }
            }
        }
    }
}