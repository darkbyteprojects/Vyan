@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.vyan.xtreamplayer.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vyan.xtreamplayer.core.ExtremeChannel
import com.vyan.xtreamplayer.data.managers.AccountManager
import com.vyan.xtreamplayer.models.AccountType
import com.vyan.xtreamplayer.data.managers.DataCache
import com.vyan.xtreamplayer.models.LiveCategory
import com.vyan.xtreamplayer.models.LiveChannel
import com.vyan.xtreamplayer.models.UserAccount
import com.vyan.xtreamplayer.data.managers.SettingsManager
import com.vyan.xtreamplayer.data.managers.UserCustomCategory
import com.vyan.xtreamplayer.network.XtreamApi
import com.vyan.xtreamplayer.utils.M3uParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

val PremiumCardGradients = listOf(
    listOf(Color(0xFFE50914), Color(0xFFB20710)),
    listOf(Color(0xFF00E5FF), Color(0xFF00838F)),
    listOf(Color(0xFF8A2BE2), Color(0xFF4A148C)),
    listOf(Color(0xFFFF9800), Color(0xFFE65100)),
    listOf(Color(0xFF4CAF50), Color(0xFF1B5E20)),
    listOf(Color(0xFFE91E63), Color(0xFF880E4F)),
    listOf(Color(0xFF3F51B5), Color(0xFF1A237E)),
    listOf(Color(0xFF00BCD4), Color(0xFF004D40)),
    listOf(Color(0xFFF44336), Color(0xFFb71c1c)),
    listOf(Color(0xFF9C27B0), Color(0xFF4A148C)),
    listOf(Color(0xFF03A9F4), Color(0xFF01579B)),
    listOf(Color(0xFF8BC34A), Color(0xFF33691E))
)

@Composable
fun LiveTVScreen(
    accountManager: AccountManager,
    settingsManager: SettingsManager,
    onCategoryClick: (LiveCategory) -> Unit,
    onAutoCategoryClick: (UserCustomCategory) -> Unit,
    onOpenArchivedFolder: () -> Unit,
    onLoginClick: () -> Unit = {},
    onSwitchPlaylistClick: () -> Unit,
    overrideAccount: UserAccount? = null,
    onPlayExtremeChannel: ((ExtremeChannel) -> Unit)? = null
) {
    val premiumBg = Color(0xFF09090B)
    val premiumSurface = Color(0xFF18181B)
    val premiumAccent = Color(0xFFFAFAFA)
    val premiumTextSec = Color(0xFFA1A1AA)
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }

    val activeAppMode = sharedPrefs.getString("active_app_mode", if (settingsManager.isLiveTvAutomatedMode) "advanced" else "basic") ?: "basic"
    val extremeBehavior = sharedPrefs.getString("extreme_livetv_behavior", "extreme") ?: "extreme"

    val isExtremeLiveTv = overrideAccount == null && activeAppMode == "extreme" && extremeBehavior == "extreme"
    val isAdvancedMode = overrideAccount == null && (activeAppMode == "advanced" || (activeAppMode == "extreme" && extremeBehavior == "advanced"))

    var currentAccount by remember {
        mutableStateOf(
            overrideAccount ?: run {
                val savedId = sharedPrefs.getString("active_livetv_account_id", null)
                accountManager.getAccounts().find { it.id == savedId } ?: accountManager.getActiveAccount()
            }
        )
    }
    var showSwitchPlaylistSheet by remember { mutableStateOf(false) }

    if (isExtremeLiveTv) {
        ExtremeChannelsScreen(settingsManager = settingsManager, onPlayExtremeChannel = onPlayExtremeChannel)
    } else if (isAdvancedMode) {
        var selectedFilter by rememberSaveable { mutableStateOf("All") }
        var brandSearchQuery by rememberSaveable { mutableStateOf("") }
        val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
        var isSearchExpanded by rememberSaveable { mutableStateOf(false) }

        BackHandler(enabled = isSearchExpanded || selectedFilter != "All") {
            if (isSearchExpanded) { isSearchExpanded = false; brandSearchQuery = "" } else selectedFilter = "All"
        }

        val activeProfileId = settingsManager.activeProfileId
        val activeProfile = remember(activeProfileId) { settingsManager.getProfiles().firstOrNull { it.id == activeProfileId } ?: settingsManager.getProfiles().first() }
        val customCatsRaw = remember(settingsManager.getCustomCategories(), activeProfile) {
            if (activeProfile.categoryIds.isEmpty()) settingsManager.getCustomCategories() else settingsManager.getCustomCategories().filter { activeProfile.categoryIds.contains(it.id) }
        }
        val dynamicFilters = remember(customCatsRaw) {
            val r = customCatsRaw.flatMap { (it.filters ?: "Global").split(",").map { f -> f.trim() } }.distinct().toMutableList()
            r.remove("All")
            listOf("All") + r.sorted()
        }
        val filteredBrands = remember(selectedFilter, brandSearchQuery, customCatsRaw) {
            customCatsRaw.filter { brand ->
                val filtersStr = brand.filters ?: "Global"
                val nameStr = brand.name ?: ""
                val matchesRegion = if (selectedFilter == "All") true else filtersStr.contains(selectedFilter)
                val matchesSearch = brandSearchQuery.isBlank() || nameStr.contains(brandSearchQuery, ignoreCase = true)
                matchesRegion && matchesSearch
            }
        }

        Box(modifier = Modifier.fillMaxSize().background(premiumBg).statusBarsPadding()) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp).animateContentSize()) {
                    if (isSearchExpanded) {
                        TextField(
                            value = brandSearchQuery, onValueChange = { brandSearchQuery = it }, modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Search curated category...", color = premiumTextSec, fontSize = 15.sp) },
                            leadingIcon = { IconButton(onClick = { isSearchExpanded = false; brandSearchQuery = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = premiumAccent) } },
                            trailingIcon = { if (brandSearchQuery.isNotEmpty()) IconButton(onClick = { brandSearchQuery = "" }) { Icon(Icons.Default.Close, null, tint = premiumAccent) } },
                            shape = CircleShape, singleLine = true,
                            colors = TextFieldDefaults.colors(focusedContainerColor = premiumSurface, unfocusedContainerColor = premiumSurface, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedTextColor = premiumAccent, unfocusedTextColor = premiumAccent)
                        )
                    } else {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Advanced TV", color = premiumAccent, fontSize = 32.sp, fontWeight = FontWeight.Black)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Profile: ${activeProfile.name}", color = premiumTextSec, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                            IconButton(onClick = { isSearchExpanded = true }, modifier = Modifier.size(42.dp).clip(CircleShape).background(premiumSurface)) {
                                Icon(Icons.Default.Search, "Search", tint = premiumAccent)
                            }
                        }
                    }
                }

                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 20.dp)) {
                    items(dynamicFilters) { filter ->
                        FilterChip(
                            selected = selectedFilter == filter, onClick = { selectedFilter = filter }, label = { Text(filter, fontWeight = FontWeight.Black, fontFamily = FontFamily.SansSerif, fontSize = 14.sp) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = premiumAccent, selectedLabelColor = premiumBg, containerColor = premiumSurface, labelColor = premiumTextSec), border = null, shape = CircleShape
                        )
                    }
                }
                if (filteredBrands.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No category found matching criteria", color = premiumTextSec, fontWeight = FontWeight.Medium) }
                } else {
                    LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 160.dp), state = gridState, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                        items(filteredBrands, key = { it.id ?: "" }) { brand ->
                            val gradient = PremiumCardGradients.getOrElse(brand.colorThemeIndex ?: 0) { PremiumCardGradients[0] }

                            Card(onClick = { onAutoCategoryClick(brand) }, modifier = Modifier.fillMaxWidth().height(130.dp), colors = CardDefaults.cardColors(containerColor = premiumSurface), shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(0.dp)) {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    Box(modifier = Modifier.fillMaxWidth().height(6.dp).background(Brush.horizontalGradient(gradient)))
                                    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Icon(Icons.Default.PlayCircleFilled, null, tint = premiumAccent, modifier = Modifier.size(32.dp)) }
                                        Column {
                                            Text(brand.name ?: "Category", color = premiumAccent, fontSize = 18.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text((brand.filters ?: "Global").split(",").firstOrNull() ?: "Global", color = premiumTextSec, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    } else {
        val activeAccount = currentAccount
        val scope = rememberCoroutineScope()

        val cacheKey = activeAccount?.id ?: ""
        var categories by remember { mutableStateOf<List<LiveCategory>>(DataCache.liveCategories[cacheKey] ?: emptyList()) }
        var isLoadingCategories by remember { mutableStateOf(categories.isEmpty() && activeAccount != null) }

        var searchQuery by rememberSaveable { mutableStateOf("") }
        var isSearchExpanded by rememberSaveable { mutableStateOf(false) }

        // ADDED FOR MULTI-SELECT HIDING
        var isEditMode by rememberSaveable { mutableStateOf(false) }
        var selectedCategoryIds by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }

        BackHandler(enabled = isSearchExpanded || isEditMode) {
            if (isEditMode) {
                isEditMode = false
                selectedCategoryIds = emptySet()
            } else {
                isSearchExpanded = false
                searchQuery = ""
            }
        }

        var hiddenCategoryIds by remember { mutableStateOf(accountManager.getHiddenCategories()) }
        var favoriteCategoryIds by remember { mutableStateOf(accountManager.getFavoriteCategories()) }

        val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

        LaunchedEffect(activeAccount?.id) {
            if (activeAccount == null) return@LaunchedEffect
            if (DataCache.liveCategories.containsKey(cacheKey)) {
                categories = DataCache.liveCategories[cacheKey] ?: emptyList()
                return@LaunchedEffect
            }
            isLoadingCategories = true
            scope.launch(Dispatchers.IO) {
                try {
                    if (activeAccount.type == AccountType.XTREAM) {
                        val response = XtreamApi.service.getLiveCategories(XtreamApi.formatApiUrl(activeAccount.url), activeAccount.username, activeAccount.pass)
                        if (response.isSuccessful && response.body() != null) { categories = response.body()!!; DataCache.liveCategories[cacheKey] = categories }
                    } else {
                        val content = if (activeAccount.type == AccountType.M3U_URL) { OkHttpClient().newCall(Request.Builder().url(activeAccount.url).build()).execute().body?.string() ?: "" } else { context.contentResolver.openInputStream(Uri.parse(activeAccount.localFilePath))?.bufferedReader()?.use { it.readText() } ?: "" }
                        val parsedChannels = M3uParser.parse(content)
                        val uniqueGroups = parsedChannels.map { it.group.ifEmpty { "Uncategorized" } }.distinct()
                        val mappedCategories = uniqueGroups.map {
                            LiveCategory(category_id = it, category_name = it, parent_id = 0)
                        }
                        categories = mappedCategories
                        DataCache.liveCategories[cacheKey] = mappedCategories
                        val channelsByGroup = parsedChannels.groupBy { it.group.ifEmpty { "Uncategorized" } }
                        channelsByGroup.forEach { (groupId, m3uChannels) -> DataCache.liveChannels["${activeAccount.id}_$groupId"] = m3uChannels.map {
                            LiveChannel(
                                num = 0, name = it.name, stream_type = "live", stream_id = it.url.hashCode(),
                                stream_icon = it.logo, epg_channel_id = it.tvgId, added = "", category_id = groupId,
                                custom_sid = "", tv_archive = 0, direct_source = it.url, tv_archive_duration = 0
                            )
                        } }
                    }
                } catch (e: Exception) { e.printStackTrace() } finally { isLoadingCategories = false }
            }
        }

        val visibleCategories = remember(categories, hiddenCategoryIds, searchQuery, favoriteCategoryIds) {
            categories.filter { !hiddenCategoryIds.contains(it.category_id) && (searchQuery.isEmpty() || it.category_name.contains(searchQuery, ignoreCase = true)) }.sortedWith(compareByDescending { favoriteCategoryIds.contains(it.category_id) })
        }

        Box(modifier = Modifier.fillMaxSize().background(premiumBg).statusBarsPadding()) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {

                // TOP BAR WITH EDIT MODE
                Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp).animateContentSize()) {
                    if (isEditMode) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { isEditMode = false; selectedCategoryIds = emptySet() }, modifier = Modifier.size(42.dp).clip(CircleShape).background(premiumSurface)) { Icon(Icons.Default.Close, "Close", tint = premiumAccent) }
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("${selectedCategoryIds.size} selected", color = premiumAccent, fontSize = 24.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                            if (selectedCategoryIds.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        selectedCategoryIds.forEach { accountManager.hideCategory(it) }
                                        hiddenCategoryIds = accountManager.getHiddenCategories()
                                        isEditMode = false
                                        selectedCategoryIds = emptySet()
                                    },
                                    modifier = Modifier.size(42.dp).clip(CircleShape).background(Color(0xFF27272A))
                                ) { Icon(Icons.Default.VisibilityOff, "Hide Selected", tint = premiumAccent) }
                            }
                        }
                    } else if (isSearchExpanded) {
                        TextField(
                            value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Search categories...", color = premiumTextSec, fontSize = 15.sp) },
                            leadingIcon = { IconButton(onClick = { isSearchExpanded = false; searchQuery = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = premiumAccent) } },
                            trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, null, tint = premiumAccent) } },
                            shape = CircleShape, singleLine = true,
                            colors = TextFieldDefaults.colors(focusedContainerColor = premiumSurface, unfocusedContainerColor = premiumSurface, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedTextColor = premiumAccent, unfocusedTextColor = premiumAccent)
                        )
                    } else {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Live TV", color = premiumAccent, fontSize = 32.sp, fontWeight = FontWeight.Black)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(if (activeAccount != null) "${visibleCategories.size} Categories" else "No server connected", color = premiumTextSec, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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
                        Column(modifier = Modifier.padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.size(64.dp), tint = premiumTextSec)
                            Spacer(modifier = Modifier.height(20.dp))
                            Text("No Server Connected", fontWeight = FontWeight.Black, fontSize = 20.sp, color = premiumAccent)
                            Spacer(modifier = Modifier.height(32.dp))
                            Button(onClick = onLoginClick, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = premiumAccent, contentColor = premiumBg)) { Text("Connect Server", fontWeight = FontWeight.Black, fontFamily = FontFamily.SansSerif, fontSize = 16.sp) }
                        }
                    }
                } else if (isLoadingCategories) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = premiumAccent) }
                } else {
                    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                        if (hiddenCategoryIds.isNotEmpty() && !isEditMode && searchQuery.isEmpty()) {
                            item {
                                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { onOpenArchivedFolder() }, colors = CardDefaults.cardColors(containerColor = Color(0xFF27272A)), shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(0.dp)) {
                                    Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Archive, contentDescription = null, tint = premiumAccent, modifier = Modifier.size(28.dp))
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Text("Archived Categories", fontWeight = FontWeight.Black, fontSize = 18.sp, color = premiumAccent)
                                    }
                                }
                            }
                        }

                        items(visibleCategories, key = { it.category_id }) { category ->
                            val isSelected = selectedCategoryIds.contains(category.category_id)
                            Card(
                                modifier = Modifier.fillMaxWidth().combinedClickable(
                                    onClick = {
                                        if (isEditMode) {
                                            selectedCategoryIds = if (isSelected) selectedCategoryIds - category.category_id else selectedCategoryIds + category.category_id
                                        } else {
                                            onCategoryClick(category)
                                        }
                                    },
                                    onLongClick = {
                                        isEditMode = true
                                        selectedCategoryIds = selectedCategoryIds + category.category_id
                                    }
                                ),
                                colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF27272A) else premiumSurface),
                                shape = RoundedCornerShape(20.dp),
                                elevation = CardDefaults.cardElevation(0.dp)
                            ) {
                                Row(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        if (isEditMode) {
                                            Icon(if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null, tint = if (isSelected) premiumAccent else premiumTextSec)
                                            Spacer(modifier = Modifier.width(16.dp))
                                        } else {
                                            Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(premiumBg), contentAlignment = Alignment.Center) { Icon(Icons.Default.Tv, null, tint = premiumTextSec, modifier = Modifier.size(24.dp)) }
                                            Spacer(modifier = Modifier.width(16.dp))
                                        }
                                        Text(category.category_name, fontWeight = FontWeight.Black, fontSize = 18.sp, color = premiumAccent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (!isEditMode) {
                                        val isFav = favoriteCategoryIds.contains(category.category_id)
                                        IconButton(onClick = { accountManager.toggleFavoriteCategory(category.category_id); favoriteCategoryIds = accountManager.getFavoriteCategories() }) { Icon(if (isFav) Icons.Default.Star else Icons.Default.StarBorder, contentDescription = "Favorite", tint = if (isFav) Color(0xFFFFD700) else premiumTextSec, modifier = Modifier.size(28.dp)) }
                                    }
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
        val activeAcc = currentAccount
        ModalBottomSheet(
            onDismissRequest = { showSwitchPlaylistSheet = false },
            containerColor = premiumSurface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            dragHandle = { BottomSheetDefaults.DragHandle(color = premiumTextSec) }
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp).navigationBarsPadding()) {
                Text("Switch Live TV Playlist", color = premiumAccent, fontSize = 24.sp, fontWeight = FontWeight.Black)
                Spacer(modifier = Modifier.height(16.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(accounts, key = { it.id }) { acc ->
                        val isSelected = acc.id == activeAcc?.id
                        Surface(
                            onClick = {
                                sharedPrefs.edit().putString("active_livetv_account_id", acc.id).apply()
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
                                        Icon(Icons.Default.Tv, null, tint = premiumTextSec, modifier = Modifier.size(20.dp))
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