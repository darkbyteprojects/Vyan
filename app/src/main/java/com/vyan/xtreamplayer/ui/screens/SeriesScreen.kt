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
import com.vyan.xtreamplayer.data.managers.AccountManager
import com.vyan.xtreamplayer.data.managers.DataCache
import com.vyan.xtreamplayer.models.Episode
import com.vyan.xtreamplayer.models.SeriesCategory
import com.vyan.xtreamplayer.models.SeriesDetailsResponse
import com.vyan.xtreamplayer.models.SeriesItem
import com.vyan.xtreamplayer.models.UserAccount
import com.vyan.xtreamplayer.network.XtreamApi
import kotlinx.coroutines.launch

@Composable
fun SeriesScreen(accountManager: AccountManager, onPlayEpisode: (SeriesItem, Episode) -> Unit, onLoginClick: () -> Unit = {}, onSwitchPlaylistClick: () -> Unit, overrideAccount: UserAccount? = null) {
    val premiumBg = Color(0xFF09090B)
    val premiumSurface = Color(0xFF18181B)
    val premiumAccent = Color(0xFFFAFAFA)
    val premiumTextSec = Color(0xFFA1A1AA)
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }

    // TAB-SPECIFIC PLAYLIST SELECTION FOR SERIES
    var currentAccount by remember {
        mutableStateOf(
            overrideAccount ?: run {
                val savedId = sharedPrefs.getString("active_series_account_id", null)
                accountManager.getAccounts().find { it.id == savedId } ?: accountManager.getActiveAccount()
            }
        )
    }
    val activeAccount = currentAccount
    val scope = rememberCoroutineScope()
    var categories by remember { mutableStateOf<List<SeriesCategory>>(emptyList()) }
    var selectedCategoryId by rememberSaveable { mutableStateOf("all") }
    var seriesList by remember { mutableStateOf<List<SeriesItem>>(emptyList()) }
    var isLoadingSeries by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedSeries by remember { mutableStateOf<SeriesItem?>(null) }
    var seriesDetails by remember { mutableStateOf<SeriesDetailsResponse?>(null) }
    var isLoadingDetails by remember { mutableStateOf(false) }
    var selectedSeasonNum by rememberSaveable { mutableStateOf("1") }
    val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    var favoriteSeriesIds by remember { mutableStateOf(accountManager.getFavoriteItems("fav_series")) }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var showSwitchPlaylistSheet by remember { mutableStateOf(false) }

    BackHandler(enabled = isSearchExpanded || selectedCategoryId != "all" || selectedSeries != null) {
        if (selectedSeries != null) selectedSeries = null else if (isSearchExpanded) { isSearchExpanded = false; searchQuery = "" } else selectedCategoryId = "all"
    }

    LaunchedEffect(activeAccount?.id) {
        if (activeAccount == null) return@LaunchedEffect
        val cacheKey = activeAccount.id
        if (DataCache.seriesCategories.containsKey(cacheKey)) {
            categories = DataCache.seriesCategories[cacheKey] ?: emptyList()
            return@LaunchedEffect
        }
        scope.launch {
            try {
                val response = XtreamApi.service.getSeriesCategories(XtreamApi.formatApiUrl(activeAccount.url), activeAccount.username, activeAccount.pass)
                if (response.isSuccessful && response.body() != null) {
                    categories = response.body()!!
                    DataCache.seriesCategories[cacheKey] = categories
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    LaunchedEffect(activeAccount?.id, selectedCategoryId) {
        if (activeAccount == null) return@LaunchedEffect
        val cacheKey = "${activeAccount.id}_$selectedCategoryId"
        if (DataCache.seriesItems.containsKey(cacheKey)) {
            seriesList = DataCache.seriesItems[cacheKey] ?: emptyList()
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
                    DataCache.seriesItems[cacheKey] = seriesList
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
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp).animateContentSize()) {
                if (isSearchExpanded) {
                    TextField(
                        value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search series...", color = premiumTextSec, fontSize = 15.sp) },
                        leadingIcon = { IconButton(onClick = { isSearchExpanded = false; searchQuery = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = premiumAccent) } },
                        trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, null, tint = premiumAccent) } },
                        shape = CircleShape, singleLine = true,
                        colors = TextFieldDefaults.colors(focusedContainerColor = premiumSurface, unfocusedContainerColor = premiumSurface, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedTextColor = premiumAccent, unfocusedTextColor = premiumAccent)
                    )
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Series", color = premiumAccent, fontSize = 32.sp, fontWeight = FontWeight.Black)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(if (activeAccount != null) "${filteredSeries.size} Series" else "No server connected", color = premiumTextSec, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(onClick = { isSearchExpanded = true }, modifier = Modifier.clip(CircleShape).background(premiumSurface).size(42.dp)) { Icon(Icons.Default.Search, contentDescription = "Search", tint = premiumAccent) }
                            if (overrideAccount == null) {
                                IconButton(onClick = { showSwitchPlaylistSheet = true }, modifier = Modifier.clip(CircleShape).background(premiumSurface).size(42.dp)) { Icon(Icons.Default.SwapHoriz, contentDescription = "Switch Playlist", tint = premiumAccent) }
                            }
                        }
                    }
                }
            }

            if (activeAccount == null) {
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), colors = CardDefaults.cardColors(containerColor = premiumSurface), shape = RoundedCornerShape(24.dp), elevation = CardDefaults.cardElevation(0.dp)) {
                    Column(modifier = Modifier.padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.CloudOff, contentDescription = null, tint = premiumTextSec, modifier = Modifier.size(64.dp))
                        Spacer(modifier = Modifier.height(20.dp))
                        Text("No Server Connected", color = premiumAccent, fontSize = 20.sp, fontWeight = FontWeight.Black)
                        Spacer(modifier = Modifier.height(32.dp))
                        Button(onClick = onLoginClick, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = premiumAccent, contentColor = premiumBg)) { Text("Connect Server", fontWeight = FontWeight.Black, fontFamily = FontFamily.SansSerif, fontSize = 16.sp) }
                    }
                }
            } else {
                if (categories.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 20.dp)) {
                        item { FilterChip(selected = selectedCategoryId == "all", onClick = { selectedCategoryId = "all" }, label = { Text("All", fontWeight = FontWeight.Black, fontFamily = FontFamily.SansSerif, fontSize = 14.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = premiumAccent, selectedLabelColor = premiumBg, containerColor = premiumSurface, labelColor = premiumTextSec), border = null, shape = CircleShape) }
                        items(categories) { cat -> FilterChip(selected = selectedCategoryId == cat.category_id, onClick = { selectedCategoryId = cat.category_id }, label = { Text(cat.category_name, fontWeight = FontWeight.Black, fontFamily = FontFamily.SansSerif, fontSize = 14.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = premiumAccent, selectedLabelColor = premiumBg, containerColor = premiumSurface, labelColor = premiumTextSec), border = null, shape = CircleShape) }
                    }
                }

                if (isLoadingSeries) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = premiumAccent) }
                } else {
                    LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 115.dp), state = gridState, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                        items(filteredSeries, key = { it.series_id }) { item ->
                            Card(onClick = { selectedSeries = item; loadSeriesDetails(item.series_id) }, colors = CardDefaults.cardColors(containerColor = premiumSurface), shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(0.dp)) {
                                Column {
                                    Box(modifier = Modifier.fillMaxWidth().aspectRatio(2f/3f).background(premiumBg)) {
                                        AsyncImage(model = item.cover, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                        val isFav = favoriteSeriesIds.contains(item.series_id.toString())
                                        IconButton(onClick = { accountManager.toggleFavoriteItem("fav_series", item.series_id.toString()); favoriteSeriesIds = accountManager.getFavoriteItems("fav_series") }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))) { Icon(if (isFav) Icons.Default.Star else Icons.Default.StarBorder, contentDescription = "Fav", tint = if (isFav) Color(0xFFFFD700) else premiumAccent, modifier = Modifier.size(20.dp)) }
                                    }
                                    Text(item.name, color = premiumAccent, fontSize = 14.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(12.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSwitchPlaylistSheet) {
        val accounts = remember { accountManager.getAccounts() }
        val activeAcc = activeAccount
        ModalBottomSheet(
            onDismissRequest = { showSwitchPlaylistSheet = false },
            containerColor = premiumSurface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            dragHandle = { BottomSheetDefaults.DragHandle(color = premiumTextSec) }
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp).navigationBarsPadding()) {
                Text("Switch Series Playlist", color = premiumAccent, fontSize = 24.sp, fontWeight = FontWeight.Black)
                Spacer(modifier = Modifier.height(16.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(accounts, key = { it.id }) { acc ->
                        val isSelected = acc.id == activeAcc?.id
                        Surface(
                            onClick = {
                                sharedPrefs.edit().putString("active_series_account_id", acc.id).apply()
                                currentAccount = acc
                                showSwitchPlaylistSheet = false
                            },
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) Color(0xFF27272A) else Color(0xFF09090B),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(premiumBg), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.VideoLibrary, null, tint = premiumTextSec, modifier = Modifier.size(20.dp))
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column {
                                        Text(acc.alias.ifEmpty { acc.username }, color = premiumAccent, fontWeight = FontWeight.Black, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(acc.url.ifEmpty { "Local M3U File" }, color = premiumTextSec, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Icon(Icons.Default.Check, null, tint = premiumAccent, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (selectedSeries != null) {
        ModalBottomSheet(onDismissRequest = { selectedSeries = null; seriesDetails = null }, containerColor = premiumBg, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
                Text(selectedSeries?.name ?: "Series Details", color = premiumAccent, fontSize = 24.sp, fontWeight = FontWeight.Black)
                Spacer(modifier = Modifier.height(20.dp))
                if (isLoadingDetails) {
                    Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = premiumAccent) }
                } else if (seriesDetails != null) {
                    val seasons = seriesDetails?.episodes?.keys?.toList() ?: emptyList()
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 20.dp)) {
                        items(seasons) { seasonNum -> FilterChip(selected = selectedSeasonNum == seasonNum, onClick = { selectedSeasonNum = seasonNum }, label = { Text("Season $seasonNum", fontWeight = FontWeight.Black, fontFamily = FontFamily.SansSerif, fontSize = 14.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = premiumAccent, selectedLabelColor = premiumBg, containerColor = premiumSurface, labelColor = premiumTextSec), border = null, shape = CircleShape) }
                    }
                    val episodes = seriesDetails?.episodes?.get(selectedSeasonNum) ?: emptyList()
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                        items(episodes) { ep ->
                            Card(onClick = { onPlayEpisode(selectedSeries!!, ep); selectedSeries = null }, colors = CardDefaults.cardColors(containerColor = premiumSurface), shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(0.dp)) {
                                Row(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.PlayCircle, contentDescription = null, tint = premiumAccent, modifier = Modifier.size(36.dp))
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("E${ep.episode_num}: ${ep.title.ifEmpty { "Episode ${ep.episode_num}" }}", color = premiumAccent, fontWeight = FontWeight.Black, fontSize = 16.sp)
                                        if (ep.info?.duration != null) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(ep.info.duration, color = premiumTextSec, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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