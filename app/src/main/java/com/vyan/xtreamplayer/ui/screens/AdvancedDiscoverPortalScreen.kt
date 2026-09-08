@file:OptIn(ExperimentalMaterial3Api::class)

package com.vyan.xtreamplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vyan.xtreamplayer.data.managers.AccountStorageManager
import com.vyan.xtreamplayer.models.AccountType
import com.vyan.xtreamplayer.models.LiveCategory
import com.vyan.xtreamplayer.models.XtreamLiveChannelModel
import com.vyan.xtreamplayer.network.ScrapedPortal
import com.vyan.xtreamplayer.models.SeriesItem
import com.vyan.xtreamplayer.data.managers.SettingsManager
import com.vyan.xtreamplayer.models.UserAccount
import com.vyan.xtreamplayer.models.VodMovie

@Composable
fun PortalPreviewScreen(portal: ScrapedPortal, accountStorageManager: AccountStorageManager, settingsManager: SettingsManager, onBack: () -> Unit, onPlayLive: (XtreamLiveChannelModel) -> Unit, onPlayMovie: (VodMovie) -> Unit, onPlaySeries: (SeriesItem) -> Unit) {
    // Telegram-Style Glass Palette
    val premiumBg = Color(0xFF0E1621)
    val premiumSurface = Color(0xFF17212B)
    val premiumAccent = Color(0xFFFFFFFF)
    val premiumTextSec = Color(0xFF7F91A4)
    val premiumBlue = Color(0xFF5288C1)

    var currentTab by remember { mutableStateOf("live_tv") }; var selectedLiveCategory by remember { mutableStateOf<LiveCategory?>(null) }; var selectedSeries by remember { mutableStateOf<SeriesItem?>(null) }
    val previewAccount = remember(portal) {
        UserAccount(
            id = "preview_${portal.username}",
            alias = portal.username,
            username = portal.username,
            pass = portal.pass,
            url = portal.url,
            type = AccountType.XTREAM
        )
    }

    Scaffold(
        containerColor = premiumBg,
        topBar = {
            Column(modifier = Modifier.fillMaxWidth().background(premiumBg).statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(modifier = Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(premiumSurface)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = premiumAccent) }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(portal.username, color = premiumAccent, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("Previewing Content", color = premiumTextSec, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        },
        bottomBar = {
            if (selectedLiveCategory == null && selectedSeries == null) {
                NavigationBar(containerColor = premiumBg, contentColor = premiumAccent, tonalElevation = 0.dp) {
                    NavigationBarItem(selected = currentTab == "live_tv", onClick = { currentTab = "live_tv" }, icon = { Icon(Icons.Default.Tv, "Live TV") }, label = { Text("Live TV", fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = premiumBg, unselectedIconColor = premiumTextSec, selectedTextColor = premiumAccent, unselectedTextColor = premiumTextSec, indicatorColor = premiumBlue))
                    NavigationBarItem(selected = currentTab == "movies", onClick = { currentTab = "movies" }, icon = { Icon(Icons.Default.Movie, "Movies") }, label = { Text("Movies", fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = premiumBg, unselectedIconColor = premiumTextSec, selectedTextColor = premiumAccent, unselectedTextColor = premiumTextSec, indicatorColor = premiumBlue))
                    NavigationBarItem(selected = currentTab == "series", onClick = { currentTab = "series" }, icon = { Icon(Icons.Default.VideoLibrary, "Series") }, label = { Text("Series", fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = premiumBg, unselectedIconColor = premiumTextSec, selectedTextColor = premiumAccent, unselectedTextColor = premiumTextSec, indicatorColor = premiumBlue))
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding).background(premiumBg)) {
            when (currentTab) {
                "live_tv" -> { if (selectedLiveCategory != null) {
                    ChannelsScreen(
                        categoryName = selectedLiveCategory!!.category_name,
                        categoryId = selectedLiveCategory!!.category_id,
                        accountStorageManager = accountStorageManager,
                        overrideAccount = previewAccount,
                        onPlayChannel = onPlayLive,
                        onBack = { selectedLiveCategory = null })
                } else {
                    LiveTVScreen(
                        accountStorageManager = accountStorageManager,
                        settingsManager = settingsManager,
                        overrideAccount = previewAccount,
                        onCategoryClick = { selectedLiveCategory = it },
                        onAutoCategoryClick = {},
                        onOpenArchivedFolder = {},
                        onSwitchPlaylistClick = {})
                } }
                "movies" -> MoviesScreen(
                    accountStorageManager = accountStorageManager,
                    overrideAccount = previewAccount,
                    onPlayMovie = onPlayMovie,
                    onSwitchPlaylistClick = {})
                "series" -> SeriesScreen(
                    accountStorageManager = accountStorageManager,
                    overrideAccount = previewAccount,
                    onPlayEpisode = { series, ep -> onPlaySeries(series) },
                    onSwitchPlaylistClick = {})
            }
        }
    }
}