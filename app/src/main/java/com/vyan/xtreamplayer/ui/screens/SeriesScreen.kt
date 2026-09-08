@file:OptIn(ExperimentalMaterial3Api::class)

package com.vyan.xtreamplayer.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vyan.xtreamplayer.data.managers.AccountStorageManager
import com.vyan.xtreamplayer.data.managers.DataCache
import com.vyan.xtreamplayer.models.Episode
import com.vyan.xtreamplayer.models.SeriesCategory
import com.vyan.xtreamplayer.models.SeriesDetailsResponse
import com.vyan.xtreamplayer.models.SeriesItem
import com.vyan.xtreamplayer.models.UserAccount
import com.vyan.xtreamplayer.network.XtreamApi
import kotlinx.coroutines.launch

@Composable
fun SeriesScreen(
    accountStorageManager: AccountStorageManager,
    onPlayEpisode: (SeriesItem, Episode) -> Unit,
    onLoginClick: () -> Unit = {},
    onSwitchPlaylistClick: () -> Unit,
    overrideAccount: UserAccount? = null
) {
    // Telegram-Style Glass Palette
    val premiumBg = Color(0xFF0E1621)
    val premiumSurface = Color(0xFF17212B)
    val premiumSurfaceVariant = Color(0xFF242F3D)
    val premiumAccent = Color(0xFFFFFFFF)
    val premiumTextSec = Color(0xFF7F91A4)
    val premiumRed = Color(0xFFE53935)
    val premiumBlue = Color(0xFF5288C1)

    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }

    var currentAccount by remember {
        mutableStateOf(
            overrideAccount ?: run {
                val savedId = sharedPrefs.getString("active_series_account_id", null)
                accountStorageManager.getAccounts().find { it.id == savedId } ?: accountStorageManager.getActiveAccount()
            }
        )
    }
    val activeAccount = currentAccount
    val scope = rememberCoroutineScope()

    var selectedCategoryId by rememberSaveable { mutableStateOf("all") }

    val cacheKeyCat = activeAccount?.id ?: ""
    val cacheKeySeries = "${activeAccount?.id}_$selectedCategoryId"

    var categories by remember { mutableStateOf<List<SeriesCategory>>(DataCache.seriesCategories[cacheKeyCat] ?: emptyList()) }
    var seriesList by remember { mutableStateOf<List<SeriesItem>>(DataCache.seriesItems[cacheKeySeries] ?: emptyList()) }

    var isLoadingSeries by remember { mutableStateOf(seriesList.isEmpty() && activeAccount != null) }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }

    var selectedSeries by remember { mutableStateOf<SeriesItem?>(null) }
    var seriesDetails by remember { mutableStateOf<SeriesDetailsResponse?>(null) }
    var isLoadingDetails by remember { mutableStateOf(false) }
    var selectedSeasonNum by rememberSaveable { mutableStateOf("1") }

    var favoriteSeriesIds by remember { mutableStateOf(accountStorageManager.getFavoriteItems("fav_series")) }
    var showSwitchPlaylistSheet by remember { mutableStateOf(false) }

    BackHandler(enabled = isSearchExpanded || selectedCategoryId != "all" || selectedSeries != null) {
        if (selectedSeries != null) selectedSeries = null
        else if (isSearchExpanded) { isSearchExpanded = false; searchQuery = "" }
        else selectedCategoryId = "all"
    }

    LaunchedEffect(activeAccount?.id) {
        if (activeAccount == null) return@LaunchedEffect
        val key = activeAccount.id
        if (DataCache.seriesCategories.containsKey(key)) {
            categories = DataCache.seriesCategories[key] ?: emptyList()
            return@LaunchedEffect
        }
        scope.launch {
            try {
                val response = XtreamApi.service.getSeriesCategories(XtreamApi.formatApiUrl(activeAccount.url), activeAccount.username, activeAccount.pass)
                if (response.isSuccessful && response.body() != null) {
                    categories = response.body()!!
                    DataCache.seriesCategories[key] = categories
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    LaunchedEffect(activeAccount?.id, selectedCategoryId) {
        if (activeAccount == null) return@LaunchedEffect
        val key = "${activeAccount.id}_$selectedCategoryId"
        if (DataCache.seriesItems.containsKey(key)) {
            seriesList = DataCache.seriesItems[key] ?: emptyList()
            isLoadingSeries = false
            return@LaunchedEffect
        }
        isLoadingSeries = true
        scope.launch {
            try {
                val targetCat = if (selectedCategoryId == "all") null else selectedCategoryId
                val response = XtreamApi.service.getSeries(XtreamApi.formatApiUrl(activeAccount.url), activeAccount.username, activeAccount.pass, targetCat)
                if (response.isSuccessful && response.body() != null) {
                    seriesList = response.body()!!
                    DataCache.seriesItems[key] = seriesList
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isLoadingSeries = false
            }
        }
    }

    fun loadSeriesDetails(seriesId: Int) {
        if (activeAccount == null) return
        isLoadingDetails = true
        scope.launch {
            try {
                val response = XtreamApi.service.getSeriesInfo(XtreamApi.formatApiUrl(activeAccount.url), activeAccount.username, activeAccount.pass, seriesId)
                if (response.isSuccessful && response.body() != null) {
                    seriesDetails = response.body()!!
                    selectedSeasonNum = seriesDetails?.episodes?.keys?.firstOrNull() ?: "1"
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isLoadingDetails = false
            }
        }
    }

    val filteredSeries = remember(seriesList, searchQuery, favoriteSeriesIds) {
        seriesList.filter { it.name.contains(searchQuery, ignoreCase = true) }.sortedWith(compareByDescending { series -> favoriteSeriesIds.contains(series.series_id.toString()) })
    }

    Box(modifier = Modifier.fillMaxSize().background(premiumBg).statusBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp).animateContentSize()) {
                if (isSearchExpanded) {
                    TextField(
                        value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search series...", color = premiumTextSec, fontSize = 15.sp) },
                        leadingIcon = { IconButton(onClick = { isSearchExpanded = false; searchQuery = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = premiumTextSec) } },
                        trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, null, tint = premiumTextSec) } },
                        shape = RoundedCornerShape(16.dp), singleLine = true,
                        colors = TextFieldDefaults.colors(focusedContainerColor = premiumSurfaceVariant, unfocusedContainerColor = premiumSurfaceVariant, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedTextColor = premiumAccent, unfocusedTextColor = premiumAccent)
                    )
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Series", color = premiumAccent, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(if (activeAccount != null) "${filteredSeries.size} Series" else "No server connected", color = premiumTextSec, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(onClick = { isSearchExpanded = true }, modifier = Modifier.clip(CircleShape).background(premiumSurface).size(42.dp)) { Icon(Icons.Default.Search, contentDescription = "Search", tint = premiumTextSec) }
                            if (overrideAccount == null) {
                                IconButton(onClick = { showSwitchPlaylistSheet = true }, modifier = Modifier.clip(CircleShape).background(premiumSurface).size(42.dp)) { Icon(Icons.Default.SwapHoriz, contentDescription = "Switch Playlist", tint = premiumTextSec) }
                            }
                        }
                    }
                }
            }

            if (activeAccount == null) {
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), colors = CardDefaults.cardColors(containerColor = premiumSurface), shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(0.dp)) {
                    Column(modifier = Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.CloudOff, contentDescription = null, tint = premiumTextSec, modifier = Modifier.size(64.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("No Server Connected", color = premiumAccent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = onLoginClick, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = premiumBlue, contentColor = premiumAccent)) { Text("Connect Server", fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, fontSize = 15.sp) }
                    }
                }
            } else {
                if (categories.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                        item { FilterChip(selected = selectedCategoryId == "all", onClick = { selectedCategoryId = "all" }, label = { Text("All", fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, fontSize = 13.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = premiumBlue, selectedLabelColor = premiumAccent, containerColor = premiumSurface, labelColor = premiumTextSec), border = null, shape = CircleShape) }
                        items(categories) { cat -> FilterChip(selected = selectedCategoryId == cat.category_id, onClick = { selectedCategoryId = cat.category_id }, label = { Text(cat.category_name, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, fontSize = 13.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = premiumBlue, selectedLabelColor = premiumAccent, containerColor = premiumSurface, labelColor = premiumTextSec), border = null, shape = CircleShape) }
                    }
                }

                if (isLoadingSeries) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = premiumBlue) }
                } else {
                    LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 115.dp), state = gridState, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                        items(filteredSeries, key = { it.series_id }) { item ->
                            Card(onClick = { selectedSeries = item; loadSeriesDetails(item.series_id) }, colors = CardDefaults.cardColors(containerColor = premiumSurface), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(0.dp)) {
                                Column {
                                    Box(modifier = Modifier.fillMaxWidth().aspectRatio(2f/3f).background(premiumBg)) {
                                        AsyncImage(model = item.cover, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                        val isFav = favoriteSeriesIds.contains(item.series_id.toString())
                                        IconButton(onClick = { accountStorageManager.toggleFavoriteItem("fav_series", item.series_id.toString()); favoriteSeriesIds = accountStorageManager.getFavoriteItems("fav_series") }, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(32.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))) { Icon(if (isFav) Icons.Default.Star else Icons.Default.StarBorder, contentDescription = "Fav", tint = if (isFav) Color(0xFFFFD700) else premiumAccent, modifier = Modifier.size(18.dp)) }
                                    }
                                    Text(item.name, color = premiumAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(10.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSwitchPlaylistSheet) {
        val accounts = remember { accountStorageManager.getAccounts() }
        val activeAcc = activeAccount
        ModalBottomSheet(
            onDismissRequest = { showSwitchPlaylistSheet = false },
            containerColor = premiumSurface,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            dragHandle = { BottomSheetDefaults.DragHandle(color = premiumTextSec) }
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp).navigationBarsPadding()) {
                Text("Switch Series Playlist", color = premiumAccent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(accounts, key = { it.id }) { acc ->
                        val isSelected = acc.id == activeAcc?.id
                        Surface(
                            onClick = {
                                sharedPrefs.edit().putString("active_series_account_id", acc.id).apply()
                                currentAccount = acc
                                showSwitchPlaylistSheet = false
                            },
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) premiumSurfaceVariant else premiumBg,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Box(modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(premiumSurface), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.VideoLibrary, null, tint = premiumTextSec, modifier = Modifier.size(18.dp))
                                    }
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column {
                                        Text(acc.alias.ifEmpty { acc.username }, color = premiumAccent, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(acc.url.ifEmpty { "Local M3U File" }, color = premiumTextSec, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Icon(Icons.Default.Check, null, tint = premiumBlue, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (selectedSeries != null) {
        ModalBottomSheet(onDismissRequest = { selectedSeries = null; seriesDetails = null }, containerColor = premiumBg, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(selectedSeries?.name ?: "Series Details", color = premiumAccent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                if (isLoadingDetails) {
                    Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = premiumBlue) }
                } else if (seriesDetails != null) {
                    val seasons = seriesDetails?.episodes?.keys?.toList() ?: emptyList()
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                        items(seasons) { seasonNum -> FilterChip(selected = selectedSeasonNum == seasonNum, onClick = { selectedSeasonNum = seasonNum }, label = { Text("Season $seasonNum", fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, fontSize = 13.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = premiumBlue, selectedLabelColor = premiumAccent, containerColor = premiumSurface, labelColor = premiumTextSec), border = null, shape = CircleShape) }
                    }
                    val episodes = seriesDetails?.episodes?.get(selectedSeasonNum) ?: emptyList()
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                        items(episodes) { ep ->
                            Card(onClick = { onPlayEpisode(selectedSeries!!, ep); selectedSeries = null }, colors = CardDefaults.cardColors(containerColor = premiumSurface), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(0.dp)) {
                                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.PlayCircle, contentDescription = null, tint = premiumBlue, modifier = Modifier.size(32.dp))
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("E${ep.episode_num}: ${ep.title.ifEmpty { "Episode ${ep.episode_num}" }}", color = premiumAccent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        if (ep.info?.duration != null) {
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(ep.info.duration, color = premiumTextSec, fontSize = 12.sp, fontWeight = FontWeight.Medium)
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
}