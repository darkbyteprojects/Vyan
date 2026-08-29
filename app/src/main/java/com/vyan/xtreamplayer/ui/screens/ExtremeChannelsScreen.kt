@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.vyan.xtreamplayer.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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

@Composable
fun ExtremeChannelsScreen(
    settingsManager: SettingsManager,
    onPlayExtremeChannel: ((ExtremeChannel) -> Unit)? = null
) {
    val premiumBg = Color(0xFF09090B)
    val premiumSurface = Color(0xFF18181B)
    val premiumAccent = Color(0xFFFAFAFA)
    val premiumTextSec = Color(0xFFA1A1AA)

    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }

    var masterConfigs by remember { mutableStateOf<List<ExtremeSourceConfig>>(emptyList()) }
    var customConfigs by remember { mutableStateOf<List<ExtremeSourceConfig>>(emptyList()) }
    var isMasterLoading by remember { mutableStateOf(true) }

    // Navigation and state variables with perfectly preserved memory
    var selectedSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var channelList by remember { mutableStateOf<List<ExtremeChannel>>(emptyList()) }
    var isFetchingChannels by remember { mutableStateOf(false) }
    var channelSearchQuery by rememberSaveable { mutableStateOf("") }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }

    fun loadCustomConfigs() {
        val jsonStr = sharedPrefs.getString("custom_extreme_sources", "[]") ?: "[]"
        val list = mutableListOf<ExtremeSourceConfig>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    ExtremeSourceConfig(
                        id = obj.optString("id"),
                        name = obj.optString("name", "Custom Source"),
                        category = obj.optString("category", "General"),
                        url = obj.optString("url", ""),
                        type = if (obj.optString("type", "M3U") == "M3U") SourceType.M3U_DIRECT else SourceType.JSON_WRAPPED,
                        image = "https://upload.wikimedia.org/wikipedia/commons/thumb/5/50/JioTV_logo.svg/1024px-JioTV_logo.svg.png"
                    )
                )
            }
        } catch (_: Exception) {}
        customConfigs = list
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            masterConfigs = ExtremeSourceRegistry.loadMasterSources(context)
            loadCustomConfigs()
            withContext(Dispatchers.Main) { isMasterLoading = false }
        }
    }

    val selectedSourceIds = remember(masterConfigs) { sharedPrefs.getStringSet("selected_extreme_sources", emptySet()) ?: emptySet() }
    val activeHubSources = remember(selectedSourceIds, masterConfigs, customConfigs) {
        masterConfigs.filter { selectedSourceIds.contains(it.id) } + customConfigs
    }

    val selectedSourceForChannels = remember(selectedSourceId, activeHubSources) {
        if (selectedSourceId == null) null
        else activeHubSources.find { it.id == selectedSourceId }
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

    BackHandler(enabled = selectedSourceForChannels != null || isSearchExpanded) {
        if (isSearchExpanded) {
            isSearchExpanded = false
            channelSearchQuery = ""
        } else {
            selectedSourceId = null
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(premiumBg).statusBarsPadding()) {
        if (isMasterLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = premiumAccent)
            }
        } else if (selectedSourceForChannels == null) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Live Extreme Channels", color = premiumAccent, fontSize = 32.sp, fontWeight = FontWeight.Black)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("${activeHubSources.size} Active Hub Sources", color = premiumTextSec, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                if (activeHubSources.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No sources added to Hub yet.", color = premiumTextSec, fontWeight = FontWeight.Medium)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 160.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 40.dp)
                    ) {
                        items(activeHubSources, key = { it.id }) { source ->
                            val isCustom = source.id.startsWith("custom_")
                            Card(
                                onClick = { selectedSourceId = source.id },
                                modifier = Modifier.fillMaxWidth().height(140.dp),
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
                                        Icon(Icons.Default.PlayCircleFilled, null, tint = premiumAccent, modifier = Modifier.size(28.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val filteredChannels = channelList.filter { channelSearchQuery.isBlank() || it.name.contains(channelSearchQuery, ignoreCase = true) }

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
                            IconButton(onClick = { selectedSourceId = null }, modifier = Modifier.clip(CircleShape).background(premiumSurface).size(42.dp)) {
                                Icon(Icons.Default.ArrowBack, "Back", tint = premiumAccent)
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(selectedSourceForChannels.name, color = premiumAccent, fontSize = 24.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                                onClick = { onPlayExtremeChannel?.invoke(channel) },
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
    }
}