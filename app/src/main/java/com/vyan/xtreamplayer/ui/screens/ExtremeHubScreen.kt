@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.vyan.xtreamplayer.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.vyan.xtreamplayer.core.ExtremeChannel
import com.vyan.xtreamplayer.core.ExtremeSourceConfig
import com.vyan.xtreamplayer.core.ExtremeSourceRegistry
import com.vyan.xtreamplayer.core.SourceType
import com.vyan.xtreamplayer.data.managers.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun ExtremeHubScreen(
    settingsManager: SettingsManager,
    onPlayExtremeChannel: (ExtremeChannel) -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }

    // PERFECT MEMORY: Screen navigation states
    var currentScreen by rememberSaveable { mutableStateOf("hub") }
    var previousScreen by rememberSaveable { mutableStateOf("hub") }

    var showAddCustomDialog by remember { mutableStateOf(false) }
    var showMenuSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    var selectedExtremeSources by remember { mutableStateOf(sharedPrefs.getStringSet("selected_extreme_sources", emptySet()) ?: emptySet()) }
    var customSourcesJson by remember { mutableStateOf(sharedPrefs.getString("custom_extreme_sources", "[]") ?: "[]") }

    var allMarketplaceSources by remember { mutableStateOf<List<ExtremeSourceConfig>>(emptyList()) }
    var customSources by remember { mutableStateOf<List<ExtremeSourceConfig>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // Channel Viewer States (Saved via ID for tab switching)
    var selectedSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var channelList by remember { mutableStateOf<List<ExtremeChannel>>(emptyList()) }
    var isFetchingChannels by remember { mutableStateOf(false) }
    var channelSearchQuery by rememberSaveable { mutableStateOf("") }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var sourceToDelete by remember { mutableStateOf<ExtremeSourceConfig?>(null) }

    // Category State for Hub
    var selectedHubCategory by rememberSaveable { mutableStateOf("All") }

    val premiumBg = Color(0xFF09090B)
    val premiumSurface = Color(0xFF18181B)
    val premiumAccent = Color(0xFFFAFAFA)
    val premiumTextSec = Color(0xFFA1A1AA)
    val softRed = Color(0xFF881337)

    fun parseCustomSources() {
        val list = mutableListOf<ExtremeSourceConfig>()
        try {
            val arr = JSONArray(customSourcesJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    ExtremeSourceConfig(
                        id = obj.optString("id"),
                        name = obj.optString("name"),
                        category = obj.optString("category", "Custom"),
                        url = obj.optString("url"),
                        type = if (obj.optString("type") == "M3U") SourceType.M3U_DIRECT else SourceType.JSON_WRAPPED,
                        image = "https://upload.wikimedia.org/wikipedia/commons/thumb/5/50/JioTV_logo.svg/1024px-JioTV_logo.svg.png"
                    )
                )
            }
        } catch (_: Exception) {}
        customSources = list
    }

    LaunchedEffect(customSourcesJson) { parseCustomSources() }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            allMarketplaceSources = ExtremeSourceRegistry.loadMasterSources(context)
            withContext(Dispatchers.Main) { isLoading = false }
        }
    }

    val selectedSourceForChannels = remember(selectedSourceId, allMarketplaceSources, customSources) {
        if (selectedSourceId == null) null
        else (allMarketplaceSources + customSources).find { it.id == selectedSourceId }
    }

    LaunchedEffect(selectedSourceForChannels) {
        if (selectedSourceForChannels != null) {
            isFetchingChannels = true
            channelList = emptyList()
            withContext(Dispatchers.IO) {
                try {
                    val channels = ExtremeSourceRegistry.fetchChannels(selectedSourceForChannels)
                    withContext(Dispatchers.Main) {
                        channelList = channels
                        isFetchingChannels = false
                    }
                } catch (_: Exception) {
                    withContext(Dispatchers.Main) { isFetchingChannels = false }
                }
            }
        }
    }

    // PERFECT BACK NAVIGATION
    BackHandler(enabled = currentScreen != "hub") {
        if (isSearchExpanded) {
            isSearchExpanded = false
            channelSearchQuery = ""
        } else if (currentScreen == "channels") {
            selectedSourceId = null
            currentScreen = previousScreen // Returns properly to Hub or Marketplace!
        } else {
            currentScreen = "hub"
        }
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize().background(premiumBg), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = premiumAccent)
        }
        return
    }

    if (currentScreen == "channels") {
        val filteredChannels = channelList.filter { channelSearchQuery.isBlank() || it.name.contains(channelSearchQuery, ignoreCase = true) }

        Box(modifier = Modifier.fillMaxSize().background(premiumBg).statusBarsPadding()) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp).animateContentSize()) {
                    if (isSearchExpanded) {
                        TextField(
                            value = channelSearchQuery, onValueChange = { channelSearchQuery = it }, modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Search channels...", color = premiumTextSec, fontSize = 15.sp) },
                            leadingIcon = { IconButton(onClick = { isSearchExpanded = false; channelSearchQuery = "" }) { Icon(Icons.Default.ArrowBack, null, tint = premiumAccent) } },
                            trailingIcon = { if (channelSearchQuery.isNotEmpty()) IconButton(onClick = { channelSearchQuery = "" }) { Icon(Icons.Default.Close, null, tint = premiumAccent) } },
                            shape = CircleShape, singleLine = true,
                            colors = TextFieldDefaults.colors(focusedContainerColor = premiumSurface, unfocusedContainerColor = premiumSurface, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedTextColor = premiumAccent, unfocusedTextColor = premiumAccent)
                        )
                    } else {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { currentScreen = previousScreen; selectedSourceId = null }, modifier = Modifier.clip(CircleShape).background(premiumSurface).size(42.dp)) {
                                Icon(Icons.Default.ArrowBack, "Back", tint = premiumAccent)
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(selectedSourceForChannels?.name ?: "Channels", color = premiumAccent, fontSize = 24.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${channelList.size} Channels Found", color = premiumTextSec, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                            IconButton(onClick = { isSearchExpanded = true }, modifier = Modifier.size(42.dp).clip(CircleShape).background(premiumSurface)) {
                                Icon(Icons.Default.Search, "Search", tint = premiumAccent)
                            }
                        }
                    }
                }

                if (isFetchingChannels) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = premiumAccent) }
                } else if (filteredChannels.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No channels found.", color = premiumTextSec) }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                        itemsIndexed(filteredChannels, key = { index, ch -> "${ch.id}_${ch.streamUrl}_$index" }) { _, channel ->
                            Card(
                                onClick = { onPlayExtremeChannel(channel) },
                                colors = CardDefaults.cardColors(containerColor = premiumSurface),
                                shape = RoundedCornerShape(20.dp),
                                elevation = CardDefaults.cardElevation(0.dp)
                            ) {
                                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    SubcomposeAsyncImage(model = channel.logo, contentDescription = null, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(premiumBg), contentScale = ContentScale.Crop, error = { Icon(Icons.Default.Tv, null, tint = premiumTextSec) })
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(channel.name, color = premiumAccent, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(channel.sourceName.ifEmpty { "Source Stream" }, color = premiumTextSec, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                            if (channel.isDrmProtected) {
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Box(modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF27272A)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                                                    Text("DRM", color = premiumAccent, fontSize = 10.sp, fontWeight = FontWeight.Black)
                                                }
                                            }
                                        }
                                    }
                                    Icon(Icons.Default.PlayCircle, null, tint = premiumAccent, modifier = Modifier.size(32.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    } else if (currentScreen == "marketplace") {
        ExtremeMarketplaceScreen(
            onBack = { currentScreen = "hub" },
            onSourceSelected = { source ->
                selectedSourceId = source.id
                previousScreen = "marketplace" // Remembers we came from marketplace!
                currentScreen = "channels"
            }
        )
        return
    } else {
        // HUB SCREEN
        val myMarketplaceSources = allMarketplaceSources.filter { selectedExtremeSources.contains(it.id) }
        val combinedHubSources = myMarketplaceSources + customSources

        val hubCategories = remember(combinedHubSources) {
            listOf("All") + combinedHubSources.map { it.category.ifBlank { "General" } }.distinct().sorted()
        }

        val filteredHubSources = remember(combinedHubSources, selectedHubCategory) {
            if (selectedHubCategory == "All") combinedHubSources
            else combinedHubSources.filter { it.category.ifBlank { "General" } == selectedHubCategory }
        }

        Box(modifier = Modifier.fillMaxSize().background(premiumBg).statusBarsPadding()) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Extreme Hub", color = premiumAccent, fontSize = 32.sp, fontWeight = FontWeight.Black)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Your installed private streams", color = premiumTextSec, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                if (combinedHubSources.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(hubCategories) { category ->
                            FilterChip(
                                selected = selectedHubCategory == category,
                                onClick = { selectedHubCategory = category },
                                label = { Text(category, fontWeight = FontWeight.Bold, fontSize = 13.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = premiumAccent,
                                    selectedLabelColor = premiumBg,
                                    containerColor = premiumSurface,
                                    labelColor = premiumTextSec
                                ),
                                border = null,
                                shape = CircleShape
                            )
                        }
                    }
                }

                if (filteredHubSources.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Storefront, null, tint = premiumTextSec, modifier = Modifier.size(64.dp))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(if(combinedHubSources.isEmpty()) "Your Hub is Empty" else "No sources in this category", color = premiumAccent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(if(combinedHubSources.isEmpty()) "Install sources from the Marketplace to begin." else "Try selecting 'All'", color = premiumTextSec, fontSize = 14.sp)
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 160.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 100.dp)
                    ) {
                        items(filteredHubSources) { source ->
                            val isCustom = source.id.startsWith("custom_")
                            Card(
                                modifier = Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(20.dp))
                                    .combinedClickable(
                                        onClick = {
                                            selectedSourceId = source.id
                                            previousScreen = "hub" // Remembers we came from hub
                                            currentScreen = "channels"
                                        },
                                        onLongClick = { if(isCustom) sourceToDelete = source }
                                    ),
                                colors = CardDefaults.cardColors(containerColor = premiumSurface),
                                shape = RoundedCornerShape(20.dp),
                                elevation = CardDefaults.cardElevation(0.dp)
                            ) {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    Box(modifier = Modifier.fillMaxWidth().height(80.dp)) {
                                        AsyncImage(model = source.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
                                        if (isCustom) {
                                            Box(modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF3B82F6)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                                Text("CUSTOM", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black)
                                            }
                                        }
                                    }
                                    Row(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(source.name, color = premiumAccent, fontSize = 15.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        IconButton(
                                            onClick = {
                                                if (isCustom) {
                                                    sourceToDelete = source
                                                } else {
                                                    val newSet = selectedExtremeSources - source.id
                                                    selectedExtremeSources = newSet
                                                    sharedPrefs.edit().putStringSet("selected_extreme_sources", newSet).apply()
                                                }
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) { Icon(Icons.Default.Delete, "Remove", tint = softRed, modifier = Modifier.size(18.dp)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            FloatingActionButton(
                onClick = { showMenuSheet = true },
                containerColor = premiumAccent,
                contentColor = premiumBg,
                shape = CircleShape,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 40.dp)
            ) {
                Icon(Icons.Default.Add, "Add Source")
            }
        }
    }

    if (showMenuSheet) {
        ModalBottomSheet(
            onDismissRequest = { showMenuSheet = false },
            sheetState = sheetState,
            containerColor = premiumSurface,
            dragHandle = { BottomSheetDefaults.DragHandle(color = premiumTextSec) }
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp).padding(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Add Source to Hub", color = premiumAccent, fontSize = 20.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(bottom = 8.dp))

                Card(onClick = { scope.launch { sheetState.hide() }.invokeOnCompletion { showMenuSheet = false; currentScreen = "marketplace" } }, colors = CardDefaults.cardColors(containerColor = softRed), shape = RoundedCornerShape(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Storefront, null, tint = premiumAccent, modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text("Browse Marketplace", color = premiumAccent, fontWeight = FontWeight.Black, fontSize = 16.sp)
                            Text("Install verified community sources", color = premiumAccent.copy(alpha = 0.8f), fontSize = 13.sp)
                        }
                    }
                }

                Card(onClick = { scope.launch { sheetState.hide() }.invokeOnCompletion { showMenuSheet = false; showAddCustomDialog = true } }, colors = CardDefaults.cardColors(containerColor = Color(0xFF27272A)), shape = RoundedCornerShape(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Link, null, tint = premiumAccent, modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text("Add Custom URL", color = premiumAccent, fontWeight = FontWeight.Black, fontSize = 16.sp)
                            Text("Manually enter an M3U or JSON link", color = premiumTextSec, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }

    if (showAddCustomDialog) {
        var sourceName by remember { mutableStateOf("") }
        var sourceUrl by remember { mutableStateOf("") }
        var sourceType by remember { mutableStateOf("M3U") }

        Dialog(onDismissRequest = { showAddCustomDialog = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(modifier = Modifier.fillMaxWidth(0.9f).wrapContentHeight(), shape = RoundedCornerShape(24.dp), color = premiumSurface) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("Add Custom Source", color = premiumAccent, fontSize = 20.sp, fontWeight = FontWeight.Black)
                    Spacer(modifier = Modifier.height(16.dp))

                    TextField(
                        value = sourceName, onValueChange = { sourceName = it },
                        placeholder = { Text("Source Name", color = premiumTextSec) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        colors = TextFieldDefaults.colors(focusedContainerColor = premiumBg, unfocusedContainerColor = premiumBg, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedTextColor = premiumAccent, unfocusedTextColor = premiumAccent),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    TextField(
                        value = sourceUrl, onValueChange = { sourceUrl = it },
                        placeholder = { Text("M3U or JSON URL", color = premiumTextSec) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        colors = TextFieldDefaults.colors(focusedContainerColor = premiumBg, unfocusedContainerColor = premiumBg, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedTextColor = premiumAccent, unfocusedTextColor = premiumAccent),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Format Type", color = premiumTextSec, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = sourceType == "M3U", onClick = { sourceType = "M3U" },
                            label = { Text("M3U Playlist") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = premiumAccent, selectedLabelColor = premiumBg, containerColor = premiumBg, labelColor = premiumTextSec),
                            border = null, shape = RoundedCornerShape(8.dp)
                        )
                        FilterChip(
                            selected = sourceType == "JSON", onClick = { sourceType = "JSON" },
                            label = { Text("JSON Wrapped") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = premiumAccent, selectedLabelColor = premiumBg, containerColor = premiumBg, labelColor = premiumTextSec),
                            border = null, shape = RoundedCornerShape(8.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showAddCustomDialog = false }) {
                            Text("Cancel", color = premiumTextSec, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (sourceName.isNotBlank() && sourceUrl.isNotBlank()) {
                                    val arr = JSONArray(customSourcesJson)
                                    val newObj = JSONObject()
                                    newObj.put("id", "custom_${System.currentTimeMillis()}")
                                    newObj.put("name", sourceName.trim())
                                    newObj.put("url", sourceUrl.trim())
                                    newObj.put("type", sourceType)
                                    newObj.put("category", "Custom")
                                    arr.put(newObj)
                                    val newJson = arr.toString()
                                    sharedPrefs.edit().putString("custom_extreme_sources", newJson).apply()
                                    customSourcesJson = newJson
                                    showAddCustomDialog = false
                                    Toast.makeText(context, "Custom Source Added", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = premiumAccent, contentColor = premiumBg)
                        ) {
                            Text("Add Source", fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }
    }

    if (sourceToDelete != null) {
        AlertDialog(
            onDismissRequest = { sourceToDelete = null },
            containerColor = premiumSurface,
            shape = RoundedCornerShape(24.dp),
            title = { Text("Delete Source?", color = premiumAccent, fontWeight = FontWeight.Black) },
            text = { Text("Are you sure you want to delete '${sourceToDelete?.name}' from your custom sources?", color = premiumTextSec) },
            confirmButton = {
                Button(
                    onClick = {
                        val arr = JSONArray(sharedPrefs.getString("custom_extreme_sources", "[]") ?: "[]")
                        val newArr = JSONArray()
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            if (obj.optString("id") != sourceToDelete?.id) {
                                newArr.put(obj)
                            }
                        }
                        val newJson = newArr.toString()
                        sharedPrefs.edit().putString("custom_extreme_sources", newJson).apply()
                        customSourcesJson = newJson
                        sourceToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = softRed, contentColor = premiumAccent)
                ) { Text("Delete", fontWeight = FontWeight.Black) }
            },
            dismissButton = { TextButton(onClick = { sourceToDelete = null }) { Text("Cancel", color = premiumTextSec, fontWeight = FontWeight.Black) } }
        )
    }
}