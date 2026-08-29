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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed // Fixed missing Grid import
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Tv
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
import com.vyan.xtreamplayer.utils.ExtremeStreamChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun ExtremeHubScreen(
    settingsManager: SettingsManager,
    onPlayExtremeChannel: ((ExtremeChannel) -> Unit)? = null
) {
    val premiumBg = Color(0xFF09090B)
    val premiumSurface = Color(0xFF18181B)
    val premiumAccent = Color(0xFFFAFAFA)
    val premiumTextSec = Color(0xFFA1A1AA)
    val premiumRed = Color(0xFFE50914)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sharedPrefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }

    var masterConfigs by remember { mutableStateOf<List<ExtremeSourceConfig>>(emptyList()) }
    var customConfigs by remember { mutableStateOf<List<ExtremeSourceConfig>>(emptyList()) }
    var isMasterLoading by remember { mutableStateOf(true) }

    var showAddSourceDialog by remember { mutableStateOf(false) }
    var isManualSyncing by remember { mutableStateOf(false) }
    var sourceToDelete by remember { mutableStateOf<ExtremeSourceConfig?>(null) }

    fun loadCustomConfigs() {
        val jsonStr = sharedPrefs.getString("custom_extreme_sources", "[]") ?: "[]"
        val list = mutableListOf<ExtremeSourceConfig>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val gId = obj.optString("groupId", "")
                list.add(
                    ExtremeSourceConfig(
                        id = obj.optString("id", "custom_${System.currentTimeMillis()}"),
                        name = obj.optString("name", "Custom Source"),
                        category = obj.optString("category", "General"),
                        groupId = if (gId.isNotBlank()) gId else null,
                        url = obj.optString("url", ""),
                        type = if (obj.optString("type", "M3U") == "M3U") SourceType.M3U_DIRECT else SourceType.JSON_WRAPPED,
                        image = "https://via.placeholder.com/400x200/18181B/FAFAFA?text=Custom+Source"
                    )
                )
            }
        } catch (_: Exception) {}
        customConfigs = list
    }

    LaunchedEffect(Unit) {
        masterConfigs = ExtremeSourceRegistry.loadMasterSources(context)
        loadCustomConfigs()
        isMasterLoading = false
    }

    val activeConfigs = remember(masterConfigs, customConfigs) { masterConfigs + customConfigs }

    var selectedGroupId by remember { mutableStateOf<String?>(null) }
    var selectedSource by remember { mutableStateOf<ExtremeSourceConfig?>(null) }
    var rawChannelList by remember { mutableStateOf<List<ExtremeChannel>>(emptyList()) }
    val displayedChannels = remember { mutableStateListOf<ExtremeChannel>() }

    var isLoading by remember { mutableStateOf(false) }
    var isTestingChannels by remember { mutableStateOf(false) }
    var testedCount by remember { mutableIntStateOf(0) }
    var workingCount by remember { mutableIntStateOf(0) }

    var fetchErrorMessage by remember { mutableStateOf<String?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var selectedGroupFilter by rememberSaveable { mutableStateOf("All") }
    var currentFetchJob by remember { mutableStateOf<Job?>(null) }
    var currentTestJob by remember { mutableStateOf<Job?>(null) }

    fun loadSourceChannels(config: ExtremeSourceConfig) {
        currentFetchJob?.cancel()
        currentTestJob?.cancel()
        rawChannelList = emptyList()
        displayedChannels.clear()
        fetchErrorMessage = null
        isLoading = true
        isTestingChannels = false

        currentFetchJob = scope.launch(Dispatchers.IO) {
            try {
                val result = ExtremeSourceRegistry.fetchChannels(config)
                withContext(Dispatchers.Main) {
                    if (selectedSource?.id == config.id) {
                        if (result.isEmpty()) {
                            fetchErrorMessage = "Source offline or 0 channels."
                        } else {
                            rawChannelList = result
                            displayedChannels.addAll(result)
                        }
                        isLoading = false
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (selectedSource?.id == config.id) {
                        fetchErrorMessage = "Network error: ${e.message}"
                        isLoading = false
                    }
                }
            }
        }
    }

    fun startChannelTesting() {
        if (rawChannelList.isEmpty() || isTestingChannels) return
        currentTestJob?.cancel()
        displayedChannels.clear()
        isTestingChannels = true
        testedCount = 0
        workingCount = 0

        currentTestJob = scope.launch {
            val sem = Semaphore(6)
            val total = rawChannelList.size
            rawChannelList.map { ch ->
                launch(Dispatchers.IO) {
                    sem.acquire()
                    try {
                        val isAlive = ExtremeStreamChecker.isStreamAlive(ch)
                        withContext(Dispatchers.Main) {
                            testedCount++
                            if (isAlive) {
                                workingCount++
                                displayedChannels.add(ch)
                            }
                        }
                    } finally {
                        sem.release()
                    }
                }
            }.joinAll()

            withContext(Dispatchers.Main) {
                isTestingChannels = false
                Toast.makeText(context, "Tested $total channels. $workingCount working.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun resetToDefault() {
        currentFetchJob?.cancel()
        currentTestJob?.cancel()
        isTestingChannels = false
        selectedSource = null
        searchQuery = ""
        isSearchExpanded = false
        selectedGroupFilter = "All"
        rawChannelList = emptyList()
        displayedChannels.clear()
    }

    BackHandler(enabled = isSearchExpanded || searchQuery.isNotEmpty() || selectedGroupFilter != "All" || selectedSource != null || selectedGroupId != null) {
        if (isSearchExpanded || searchQuery.isNotEmpty()) {
            searchQuery = ""
            isSearchExpanded = false
        } else if (selectedGroupFilter != "All") {
            selectedGroupFilter = "All"
        } else if (selectedSource != null) {
            resetToDefault()
        } else if (selectedGroupId != null) {
            selectedGroupId = null
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(premiumBg).statusBarsPadding()) {
        if (isMasterLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = premiumAccent)
            }
        }
        // LEVEL 1: GROUPS & STANDALONE SOURCES
        else if (selectedGroupId == null && selectedSource == null) {
            val groupedCategories = activeConfigs.filter { it.groupId != null }.groupBy { it.groupId!! }
            val standaloneSources = activeConfigs.filter { it.groupId == null }

            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Extreme Hub", color = premiumAccent, fontSize = 32.sp, fontWeight = FontWeight.Black)
                        Text("${groupedCategories.size} Groups • ${standaloneSources.size} Standalone", color = premiumTextSec, fontSize = 14.sp)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(
                            onClick = { showAddSourceDialog = true },
                            modifier = Modifier.clip(CircleShape).background(premiumSurface).size(44.dp)
                        ) { Icon(Icons.Default.Add, "Add Custom Source", tint = premiumAccent, modifier = Modifier.size(24.dp)) }

                        IconButton(
                            onClick = {
                                if (!isManualSyncing) {
                                    isManualSyncing = true
                                    scope.launch(Dispatchers.IO) {
                                        ExtremeHubAggregator.syncSources(activeConfigs)
                                        withContext(Dispatchers.Main) {
                                            isManualSyncing = false
                                            Toast.makeText(context, "Sources Reloaded", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.size(44.dp).clip(CircleShape).background(premiumSurface)
                        ) {
                            if (isManualSyncing) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = premiumAccent)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "Reload Sources", tint = premiumAccent, modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 40.dp)
                ) {
                    items(groupedCategories.keys.toList()) { groupId ->
                        val firstSource = groupedCategories[groupId]?.firstOrNull()
                        val folderImage = ExtremeSourceRegistry.groupImages[groupId] ?: firstSource?.image

                        Card(
                            onClick = { selectedGroupId = groupId },
                            modifier = Modifier.fillMaxWidth().height(150.dp),
                            colors = CardDefaults.cardColors(containerColor = premiumSurface),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                AsyncImage(model = folderImage, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().weight(1f).background(premiumBg))
                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) { Text("$groupId (${groupedCategories[groupId]?.size})", color = premiumAccent, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }

                    items(standaloneSources, key = { it.id }) { config ->
                        Card(
                            modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(20.dp))
                                .combinedClickable(
                                    onClick = { selectedSource = config; loadSourceChannels(config) },
                                    onLongClick = { if (config.id.startsWith("custom_")) sourceToDelete = config }
                                ),
                            colors = CardDefaults.cardColors(containerColor = premiumSurface),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                AsyncImage(model = config.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().weight(1f).background(premiumBg))
                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) { Text(config.name, color = premiumAccent, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }
            }
        }
        // LEVEL 2: SOURCES IN GROUP
        else if (selectedSource == null && selectedGroupId != null) {
            val sourcesInGroup = activeConfigs.filter { it.groupId == selectedGroupId }
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { selectedGroupId = null },
                        modifier = Modifier.clip(CircleShape).background(premiumSurface).size(42.dp)
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = premiumAccent) }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(selectedGroupId!!, color = premiumAccent, fontSize = 24.sp, fontWeight = FontWeight.Black)
                        Text("${sourcesInGroup.size} Sources Available", color = premiumTextSec, fontSize = 13.sp)
                    }
                }

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 40.dp)
                ) {
                    items(sourcesInGroup, key = { it.id }) { config ->
                        Card(
                            modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(20.dp))
                                .combinedClickable(
                                    onClick = { selectedSource = config; loadSourceChannels(config) },
                                    onLongClick = { if (config.id.startsWith("custom_")) sourceToDelete = config }
                                ),
                            colors = CardDefaults.cardColors(containerColor = premiumSurface),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                AsyncImage(model = config.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().weight(1f).background(premiumBg))
                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) { Text(config.name, color = premiumAccent, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }
            }
        }
        // LEVEL 3: CHANNELS IN SOURCE WITH TEST BUTTON
        else {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { resetToDefault() },
                        modifier = Modifier.clip(CircleShape).background(premiumSurface).size(42.dp)
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = premiumAccent) }
                    Spacer(modifier = Modifier.width(16.dp))

                    if (isSearchExpanded) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Search channels...", color = premiumTextSec) },
                            shape = CircleShape, singleLine = true,
                            colors = TextFieldDefaults.colors(focusedContainerColor = premiumSurface, unfocusedContainerColor = premiumSurface, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedTextColor = premiumAccent)
                        )
                    } else {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(selectedSource!!.name, color = premiumAccent, fontSize = 24.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (isTestingChannels) "Testing $testedCount / ${rawChannelList.size} ($workingCount working)..."
                                else "${displayedChannels.size} Channels Loaded",
                                color = if (isTestingChannels) Color(0xFF3B82F6) else premiumTextSec,
                                fontSize = 13.sp
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { isSearchExpanded = true }, modifier = Modifier.clip(CircleShape).background(premiumSurface).size(42.dp)) {
                                Icon(Icons.Default.Search, "Search", tint = premiumAccent)
                            }

                            // Manual Video Chunk Test Button
                            IconButton(
                                onClick = { startChannelTesting() },
                                modifier = Modifier.clip(CircleShape).background(if (isTestingChannels) Color(0xFF3B82F6) else premiumSurface).size(42.dp)
                            ) {
                                if (isTestingChannels) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                                } else {
                                    Icon(Icons.Default.Speed, contentDescription = "Test Streams", tint = premiumAccent)
                                }
                            }
                        }
                    }
                }

                val distinctGroups = remember(displayedChannels.size) { listOf("All") + displayedChannels.map { it.group }.distinct().filter { it.isNotBlank() }.sorted() }

                if (distinctGroups.size > 2) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                        items(distinctGroups) { group ->
                            FilterChip(
                                selected = selectedGroupFilter == group, onClick = { selectedGroupFilter = group },
                                label = { Text(group, fontSize = 13.sp) }, shape = CircleShape,
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = premiumAccent, selectedLabelColor = premiumBg, containerColor = premiumSurface, labelColor = premiumTextSec)
                            )
                        }
                    }
                }

                val filteredChannels = remember(displayedChannels.size, searchQuery, selectedGroupFilter) {
                    displayedChannels.filter { (searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true)) && (selectedGroupFilter == "All" || it.group.equals(selectedGroupFilter, ignoreCase = true)) }
                }

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = premiumAccent)
                    }
                } else if (fetchErrorMessage != null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(fetchErrorMessage!!, color = premiumRed, fontWeight = FontWeight.Bold)
                    }
                } else if (filteredChannels.isNotEmpty()) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(1),
                        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        // FIX APPLIED HERE: using `itemsIndexed` imported correctly from grid foundation
                        itemsIndexed(filteredChannels, key = { index, channel -> "${channel.id}_$index" }) { _, channel ->
                            Card(
                                onClick = { onPlayExtremeChannel?.invoke(channel) }, modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = premiumSurface), shape = RoundedCornerShape(20.dp)
                            ) {
                                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (channel.logo.isNotBlank()) {
                                        SubcomposeAsyncImage(model = channel.logo, contentDescription = null, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(premiumBg), contentScale = ContentScale.Crop)
                                    } else {
                                        Box(modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(premiumBg), contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Tv, null, tint = premiumTextSec, modifier = Modifier.size(28.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(channel.name, color = premiumAccent, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(channel.group, color = premiumTextSec, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Icon(Icons.Default.PlayCircle, null, tint = premiumAccent, modifier = Modifier.size(32.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Add Custom Source Dialog (With Optional Group / Folder Input)
        if (showAddSourceDialog) {
            var newName by remember { mutableStateOf("") }
            var newGroup by remember { mutableStateOf("") }
            var newUrl by remember { mutableStateOf("") }
            var newType by remember { mutableStateOf("M3U") }

            AlertDialog(
                onDismissRequest = { showAddSourceDialog = false },
                containerColor = premiumSurface,
                shape = RoundedCornerShape(24.dp),
                title = { Text("Add Custom Source", color = premiumAccent, fontWeight = FontWeight.Black, fontSize = 22.sp) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedTextField(
                            value = newName, onValueChange = { newName = it }, label = { Text("Source Name", color = premiumTextSec) },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = premiumAccent, unfocusedTextColor = premiumAccent, focusedBorderColor = premiumAccent, unfocusedBorderColor = premiumTextSec)
                        )
                        OutlinedTextField(
                            value = newGroup, onValueChange = { newGroup = it }, label = { Text("Group / Folder (Optional)", color = premiumTextSec) },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = premiumAccent, unfocusedTextColor = premiumAccent, focusedBorderColor = premiumAccent, unfocusedBorderColor = premiumTextSec)
                        )
                        OutlinedTextField(
                            value = newUrl, onValueChange = { newUrl = it }, label = { Text("URL (M3U or JSON)", color = premiumTextSec) },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = premiumAccent, unfocusedTextColor = premiumAccent, focusedBorderColor = premiumAccent, unfocusedBorderColor = premiumTextSec)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilterChip(selected = newType == "M3U", onClick = { newType = "M3U" }, label = { Text("M3U", fontWeight = FontWeight.Black) }, shape = CircleShape, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = premiumAccent, selectedLabelColor = premiumBg, containerColor = premiumBg, labelColor = premiumTextSec))
                            FilterChip(selected = newType == "JSON", onClick = { newType = "JSON" }, label = { Text("JSON", fontWeight = FontWeight.Black) }, shape = CircleShape, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = premiumAccent, selectedLabelColor = premiumBg, containerColor = premiumBg, labelColor = premiumTextSec))
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newName.isNotBlank() && newUrl.isNotBlank()) {
                                val arr = JSONArray(sharedPrefs.getString("custom_extreme_sources", "[]") ?: "[]")
                                val newObj = JSONObject().apply {
                                    put("id", "custom_${System.currentTimeMillis()}")
                                    put("name", newName.trim())
                                    put("url", newUrl.trim())
                                    put("type", newType)
                                    if (newGroup.trim().isNotEmpty()) {
                                        put("groupId", newGroup.trim())
                                    }
                                }
                                arr.put(newObj)
                                sharedPrefs.edit().putString("custom_extreme_sources", arr.toString()).apply()
                                loadCustomConfigs()
                                showAddSourceDialog = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = premiumAccent, contentColor = premiumBg)
                    ) { Text("Save", fontWeight = FontWeight.Black) }
                },
                dismissButton = { TextButton(onClick = { showAddSourceDialog = false }) { Text("Cancel", color = premiumTextSec, fontWeight = FontWeight.Black) } }
            )
        }

        // Delete Custom Source Dialog
        if (sourceToDelete != null) {
            AlertDialog(
                onDismissRequest = { sourceToDelete = null },
                containerColor = premiumSurface,
                shape = RoundedCornerShape(24.dp),
                title = { Text("Delete Source?", color = premiumAccent, fontWeight = FontWeight.Black) },
                text = { Text("Are you sure you want to delete '${sourceToDelete?.name}'?", color = premiumTextSec) },
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
                            sharedPrefs.edit().putString("custom_extreme_sources", newArr.toString()).apply()
                            loadCustomConfigs()
                            sourceToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = premiumRed, contentColor = premiumAccent)
                    ) { Text("Delete", fontWeight = FontWeight.Black) }
                },
                dismissButton = { TextButton(onClick = { sourceToDelete = null }) { Text("Cancel", color = premiumTextSec, fontWeight = FontWeight.Black) } }
            )
        }
    }
}