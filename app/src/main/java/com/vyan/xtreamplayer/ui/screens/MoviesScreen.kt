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
import com.vyan.xtreamplayer.models.UserAccount
import com.vyan.xtreamplayer.models.VodCategory
import com.vyan.xtreamplayer.models.VodMovie
import com.vyan.xtreamplayer.network.XtreamApi
import kotlinx.coroutines.launch

@Composable
fun MoviesScreen(
    accountManager: AccountManager,
    onPlayMovie: (VodMovie) -> Unit,
    onLoginClick: () -> Unit = {},
    onSwitchPlaylistClick: () -> Unit,
    overrideAccount: UserAccount? = null
) {
    val premiumBg = Color(0xFF09090B)
    val premiumSurface = Color(0xFF18181B)
    val premiumAccent = Color(0xFFFAFAFA)
    val premiumTextSec = Color(0xFFA1A1AA)
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }

    // TAB-SPECIFIC PLAYLIST SELECTION FOR MOVIES
    var currentAccount by remember {
        mutableStateOf(
            overrideAccount ?: run {
                val savedId = sharedPrefs.getString("active_movies_account_id", null)
                accountManager.getAccounts().find { it.id == savedId } ?: accountManager.getActiveAccount()
            }
        )
    }
    val activeAccount = currentAccount
    val scope = rememberCoroutineScope()

    // PERFECT MEMORY: explicitly save selected category
    var selectedCategoryId by rememberSaveable { mutableStateOf("all") }

    val cacheKeyCat = activeAccount?.id ?: ""
    val cacheKeyMovies = "${activeAccount?.id}_$selectedCategoryId"

    // PERFECT MEMORY: Initialize data instantly from cache to prevent scroll loss
    var categories by remember { mutableStateOf<List<VodCategory>>(DataCache.vodCategories[cacheKeyCat] ?: emptyList()) }
    var movies by remember { mutableStateOf<List<VodMovie>>(DataCache.vodMovies[cacheKeyMovies] ?: emptyList()) }

    var isLoadingCategories by remember { mutableStateOf(categories.isEmpty() && activeAccount != null) }
    var isLoadingMovies by remember { mutableStateOf(movies.isEmpty() && activeAccount != null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // PERFECT MEMORY: Explicitly save search query and grid state
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }

    var favoriteMovieIds by remember { mutableStateOf(accountManager.getFavoriteItems("fav_movies")) }
    var showSwitchPlaylistSheet by remember { mutableStateOf(false) }

    BackHandler(enabled = isSearchExpanded || selectedCategoryId != "all") {
        if (isSearchExpanded) { isSearchExpanded = false; searchQuery = "" } else selectedCategoryId = "all"
    }

    LaunchedEffect(activeAccount?.id) {
        if (activeAccount == null) return@LaunchedEffect
        val key = activeAccount.id
        if (DataCache.vodCategories.containsKey(key)) {
            categories = DataCache.vodCategories[key] ?: emptyList()
            return@LaunchedEffect
        }
        isLoadingCategories = true
        scope.launch {
            try {
                val response = XtreamApi.service.getVodCategories(XtreamApi.formatApiUrl(activeAccount.url), activeAccount.username, activeAccount.pass)
                if (response.isSuccessful && response.body() != null) {
                    categories = response.body()!!
                    DataCache.vodCategories[key] = categories
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isLoadingCategories = false
            }
        }
    }

    LaunchedEffect(activeAccount?.id, selectedCategoryId) {
        if (activeAccount == null) return@LaunchedEffect
        val key = "${activeAccount.id}_$selectedCategoryId"
        if (DataCache.vodMovies.containsKey(key)) {
            movies = DataCache.vodMovies[key] ?: emptyList()
            isLoadingMovies = false
            return@LaunchedEffect
        }
        isLoadingMovies = true
        errorMessage = null
        scope.launch {
            try {
                val targetCat = if (selectedCategoryId == "all") null else selectedCategoryId
                val response = XtreamApi.service.getVodStreams(XtreamApi.formatApiUrl(activeAccount.url), activeAccount.username, activeAccount.pass, targetCat)
                if (response.isSuccessful && response.body() != null) {
                    movies = response.body()!!
                    DataCache.vodMovies[key] = movies
                } else {
                    errorMessage = "Failed to load VOD movies"
                }
            } catch (e: Exception) {
                errorMessage = "Error: ${e.localizedMessage}"
            } finally {
                isLoadingMovies = false
            }
        }
    }

    val filteredMovies = remember(movies, searchQuery, favoriteMovieIds) {
        movies.filter { it.name.contains(searchQuery, ignoreCase = true) }.sortedWith(compareByDescending { movie -> favoriteMovieIds.contains(movie.stream_id.toString()) })
    }

    Box(modifier = Modifier.fillMaxSize().background(premiumBg).statusBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp).animateContentSize()) {
                if (isSearchExpanded) {
                    TextField(
                        value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search movies...", color = premiumTextSec, fontSize = 15.sp) },
                        leadingIcon = { IconButton(onClick = { isSearchExpanded = false; searchQuery = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = premiumAccent) } },
                        trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, null, tint = premiumAccent) } },
                        shape = CircleShape, singleLine = true,
                        colors = TextFieldDefaults.colors(focusedContainerColor = premiumSurface, unfocusedContainerColor = premiumSurface, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedTextColor = premiumAccent, unfocusedTextColor = premiumAccent)
                    )
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Movies", color = premiumAccent, fontSize = 32.sp, fontWeight = FontWeight.Black)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(if (activeAccount != null) "${filteredMovies.size} Movies" else "No server connected", color = premiumTextSec, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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

                if (isLoadingMovies || isLoadingCategories) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = premiumAccent) }
                } else if (errorMessage != null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(errorMessage!!, color = Color(0xFFE50914), fontWeight = FontWeight.Bold) }
                } else {
                    LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 115.dp), state = gridState, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                        items(filteredMovies, key = { it.stream_id }) { movie ->
                            Card(onClick = { onPlayMovie(movie) }, colors = CardDefaults.cardColors(containerColor = premiumSurface), shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(0.dp)) {
                                Column {
                                    Box(modifier = Modifier.fillMaxWidth().aspectRatio(2f/3f).background(premiumBg)) {
                                        AsyncImage(model = movie.stream_icon, contentDescription = movie.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                        val isFav = favoriteMovieIds.contains(movie.stream_id.toString())
                                        IconButton(onClick = { accountManager.toggleFavoriteItem("fav_movies", movie.stream_id.toString()); favoriteMovieIds = accountManager.getFavoriteItems("fav_movies") }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))) { Icon(if (isFav) Icons.Default.Star else Icons.Default.StarBorder, contentDescription = "Fav", tint = if (isFav) Color(0xFFFFD700) else premiumAccent, modifier = Modifier.size(20.dp)) }
                                    }
                                    Text(movie.name, color = premiumAccent, fontSize = 14.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(12.dp))
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
                Text("Switch Movies Playlist", color = premiumAccent, fontSize = 24.sp, fontWeight = FontWeight.Black)
                Spacer(modifier = Modifier.height(16.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(accounts, key = { it.id }) { acc ->
                        val isSelected = acc.id == activeAcc?.id
                        Surface(
                            onClick = {
                                sharedPrefs.edit().putString("active_movies_account_id", acc.id).apply()
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
                                        Icon(Icons.Default.Movie, null, tint = premiumTextSec, modifier = Modifier.size(20.dp))
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
}