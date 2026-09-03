@file:OptIn(ExperimentalMaterial3Api::class)

package com.vyan.xtreamplayer.ui.screens

import android.content.Context
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import coil.request.ImageRequest
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vyan.xtreamplayer.data.managers.AccountManager
import com.vyan.xtreamplayer.network.CatalogScraper
import com.vyan.xtreamplayer.network.CatalogSource
import com.vyan.xtreamplayer.data.managers.DataCache
import com.vyan.xtreamplayer.core.HardcodedChannels
import com.vyan.xtreamplayer.utils.IptvAliveChecker
import com.vyan.xtreamplayer.utils.NetworkClient
import com.vyan.xtreamplayer.network.IptvVerifier
import com.vyan.xtreamplayer.network.ScrapedPortal
import com.vyan.xtreamplayer.data.managers.SettingsManager
import com.vyan.xtreamplayer.data.managers.UserCustomCategory
import com.vyan.xtreamplayer.network.XtreamApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

data class AggregatedChannel(val streamId: Int, val name: String, val icon: String?, val streamUrl: String, val sourceName: String, val portalUrl: String, val username: String, val pass: String)

fun saveDiscoveredPortal(context: Context, portal: ScrapedPortal) {
    val prefs = context.getSharedPreferences("DiscoverPrefs", Context.MODE_PRIVATE); val gson = Gson(); val savedJson = prefs.getString("saved_portals", null); val type = object : TypeToken<List<ScrapedPortal>>() {}.type
    val existing: MutableList<ScrapedPortal> = if (savedJson != null) gson.fromJson(savedJson, type) ?: mutableListOf() else mutableListOf()
    if (existing.none { it.url == portal.url && it.username == portal.username }) { existing.add(portal); prefs.edit().putString("saved_portals", gson.toJson(existing)).apply() }
}

@Composable
fun HitEpgRow(channel: AggregatedChannel) {
    val premiumTextSec = Color(0xFFA1A1AA); val premiumRed = Color(0xFFE50914); val premiumAccent = Color(0xFFFAFAFA)
    var epgText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(channel.streamUrl) {
        val cacheKey = "${channel.portalUrl}|${channel.streamId}"
        if (DataCache.hitEpgCache.containsKey(cacheKey)) { epgText = DataCache.hitEpgCache[cacheKey]; return@LaunchedEffect }
        try {
            val result = withContext(Dispatchers.IO) {
                val epgUrl = "${channel.portalUrl}/player_api.php?username=${channel.username}&password=${channel.pass}&action=get_short_epg&stream_id=${channel.streamId}&limit=1"
                val response = NetworkClient.defaultClient.newCall(Request.Builder().url(epgUrl).build()).execute()
                if (response.isSuccessful) {
                    val root = JSONObject(response.body?.string() ?: return@withContext null)
                    val listings = root.optJSONArray("epg_listings")
                    if (listings != null && listings.length() > 0) {
                        var title = listings.getJSONObject(0).optString("title", "")
                        try { title = String(Base64.decode(title, Base64.DEFAULT)) } catch (e: Exception) {}
                        return@withContext title.trim().ifEmpty { null }
                    }
                }
                null
            }
            if (result != null) { DataCache.hitEpgCache[cacheKey] = result; epgText = result } else DataCache.hitEpgCache[cacheKey] = ""
        } catch (e: Exception) { DataCache.hitEpgCache[cacheKey] = "" }
    }
    if (!epgText.isNullOrEmpty()) {
        Row(modifier = Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(premiumRed).padding(horizontal = 6.dp, vertical = 2.dp)) { Text("NOW", color = premiumAccent, fontSize = 8.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.SansSerif) }
            Spacer(modifier = Modifier.width(6.dp))
            Text(epgText!!, color = premiumTextSec, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun AutoChannelsScreen(channelConfig: UserCustomCategory, accountManager: AccountManager, settingsManager: SettingsManager, onPlayChannel: (String, String, List<AggregatedChannel>) -> Unit, onBack: () -> Unit) {
    val premiumBg = Color(0xFF09090B); val premiumSurface = Color(0xFF18181B); val premiumAccent = Color(0xFFFAFAFA); val premiumTextSec = Color(0xFFA1A1AA); val premiumRed = Color(0xFFE50914)
    val context = LocalContext.current; val scope = rememberCoroutineScope()

    // PERFECT MEMORY: Remembers exact scroll position when returning from the player
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    val aggregatedChannels = remember { mutableStateListOf<AggregatedChannel>() }
    var isLoading by remember { mutableStateOf(true) }
    var isScrapingMore by remember { mutableStateOf(false) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var isReverifying by remember { mutableStateOf(false) }
    var reverifyProgress by remember { mutableStateOf("") }

    // PERFECT MEMORY: Remembers search query and folder view modes
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var isFolderView by rememberSaveable { mutableStateOf(false) }

    var statusText by remember { mutableStateOf("Aggregating channels from all portals...") }
    var expandedFolders by remember { mutableStateOf<Set<String>>(emptySet()) }
    var scrapeSource by remember { mutableStateOf(CatalogSource.BEST) }
    var catalogAfter by remember { mutableStateOf<String?>(null) }

    val pendingPortals = remember { mutableListOf<ScrapedPortal>() }
    val attemptedKeys = remember { mutableSetOf<String>() }

    // INTELLIGENT BACK HANDLER
    BackHandler(enabled = isSearchExpanded || isFolderView || isScrapingMore) {
        if (isScrapingMore) { scanJob?.cancel(); isScrapingMore = false; isLoading = false; statusText = "Scan stopped." }
        else if (isSearchExpanded) { isSearchExpanded = false; searchQuery = "" }
        else if (isFolderView) { isFolderView = false }
    }

    fun loadInitialChannels() {
        val cacheKey = channelConfig.id ?: "unknown_category"
        if (aggregatedChannels.isNotEmpty()) { isLoading = false; return }; isLoading = true
        scope.launch(Dispatchers.IO) {
            DataCache.loadPersistentCacheIfNeeded(context)
            val existingCachedChannels = DataCache.aggregatedChannelsCache[cacheKey] ?: emptyList()
            if (existingCachedChannels.isNotEmpty()) { withContext(Dispatchers.Main) { aggregatedChannels.clear(); aggregatedChannels.addAll(existingCachedChannels); isLoading = false }; return@launch }

            val allCurrentPortals = mutableListOf<Triple<String, String, String>>(); val portalNames = mutableMapOf<String, String>()
            val activeProfile = settingsManager.getProfiles().firstOrNull { it.id == settingsManager.activeProfileId } ?: settingsManager.getProfiles().first()

            if (activeProfile.useFreePortals) {
                val prefs = context.getSharedPreferences("DiscoverPrefs", Context.MODE_PRIVATE); val savedJson = prefs.getString("saved_portals", null)
                if (savedJson != null) { try { val scraped: List<ScrapedPortal> = Gson().fromJson(savedJson, object : TypeToken<List<ScrapedPortal>>() {}.type); scraped.forEach { allCurrentPortals.add(Triple(it.url, it.username, it.pass)); portalNames["${it.url}|${it.username}"] = it.username; attemptedKeys.add("${it.username}|${it.pass}".lowercase()) } } catch (e: Exception) {} }
            }
            accountManager.getAccounts().forEach { acc ->
                if (activeProfile.playlistIds.isEmpty() || activeProfile.playlistIds.contains(acc.id)) { allCurrentPortals.add(Triple(acc.url, acc.username, acc.pass)); portalNames["${acc.url}|${acc.username}"] = acc.alias.ifEmpty { acc.username }.ifEmpty { "Local File" }; attemptedKeys.add("${acc.username}|${acc.pass}".lowercase()) }
            }
            val currentPortalKeys = allCurrentPortals.map { "${it.first}|${it.second}" }.toSet()
            withContext(Dispatchers.Main) { statusText = "Scanning ${allCurrentPortals.size} portals..." }

            val jobs = allCurrentPortals.map { (url, user, pass) ->
                launch {
                    try {
                        val response = XtreamApi.service.getLiveStreams(XtreamApi.formatApiUrl(url), user, pass, null)
                        if (response.isSuccessful) {
                            val allChannels = response.body() ?: emptyList()
                            val keywords = (channelConfig.keywords ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }; val excludes = (channelConfig.exclude ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            val newHits = allChannels.filter { HardcodedChannels.matches(it.name, keywords, excludes) }.map { ch -> AggregatedChannel(streamId = ch.stream_id, name = ch.name, icon = ch.stream_icon, streamUrl = XtreamApi.buildLiveStreamUrl(url, user, pass, ch.stream_id), sourceName = portalNames["$url|$user"] ?: "Local File", portalUrl = url, username = user, pass = pass) }
                            if (newHits.isNotEmpty()) {
                                val uniqueNewHits = newHits.filter { hit -> aggregatedChannels.none { it.streamUrl == hit.streamUrl } }
                                if (uniqueNewHits.isNotEmpty()) {
                                    withContext(Dispatchers.Main) { statusText = "Verifying ${uniqueNewHits.size} channels..." }
                                    val sem = Semaphore(8)
                                    val verifyJobs = uniqueNewHits.map { hit -> launch { sem.acquire(); try { if (IptvAliveChecker.isStreamAlive(hit.streamUrl)) { withContext(Dispatchers.Main) { aggregatedChannels.add(hit) } } } finally { sem.release() } } }
                                    verifyJobs.joinAll()
                                }
                            }
                        }
                    } catch (e: Exception) {}
                }
            }
            jobs.joinAll()
            withContext(Dispatchers.Main) { DataCache.aggregatedChannelsCache[cacheKey] = aggregatedChannels.toList(); DataCache.scannedPortalsTracker[cacheKey] = currentPortalKeys; DataCache.savePersistentCache(context); isLoading = false }
        }
    }
    LaunchedEffect(channelConfig.id) { loadInitialChannels() }

    val filteredChannels = remember(searchQuery, aggregatedChannels.size) { if (searchQuery.isBlank()) aggregatedChannels.toList() else aggregatedChannels.filter { it.name.contains(searchQuery, ignoreCase = true) } }
    val groupedChannels = remember(filteredChannels) { filteredChannels.groupBy { it.username } }

    Column(modifier = Modifier.fillMaxSize().background(premiumBg).statusBarsPadding()) {
        Column(modifier = Modifier.weight(1f).padding(horizontal = 20.dp)) {

            Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp).animateContentSize()) {
                if (isSearchExpanded) {
                    TextField(
                        value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search hits...", color = premiumTextSec, fontSize = 15.sp) },
                        leadingIcon = { IconButton(onClick = { isSearchExpanded = false; searchQuery = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = premiumAccent) } },
                        trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, null, tint = premiumAccent) } },
                        shape = CircleShape, singleLine = true,
                        colors = TextFieldDefaults.colors(focusedContainerColor = premiumSurface, unfocusedContainerColor = premiumSurface, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedTextColor = premiumAccent, unfocusedTextColor = premiumAccent)
                    )
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack, modifier = Modifier.clip(CircleShape).background(premiumSurface).size(42.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = premiumAccent) }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(channelConfig.name ?: "Category", color = premiumAccent, fontSize = 24.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (isReverifying) reverifyProgress else "${aggregatedChannels.size} Channels Aggregated", color = premiumTextSec, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        if (isLoading) { Box(modifier = Modifier.size(42.dp).clip(CircleShape).background(premiumSurface), contentAlignment = Alignment.Center) { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = premiumAccent) }; Spacer(modifier = Modifier.width(8.dp)) }
                        else if (aggregatedChannels.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    if (isReverifying) return@IconButton
                                    isReverifying = true
                                    scope.launch(Dispatchers.IO) {
                                        val currentList = aggregatedChannels.toList(); withContext(Dispatchers.Main) { aggregatedChannels.clear() }; var checked = 0; val semaphore = Semaphore(8)
                                        try {
                                            val verifyJobs = currentList.map { channel -> launch { semaphore.acquire(); try { if (IptvAliveChecker.isStreamAlive(channel.streamUrl)) { withContext(Dispatchers.Main) { aggregatedChannels.add(channel) } }; checked++; withContext(Dispatchers.Main) { reverifyProgress = "Verifying $checked / ${currentList.size}..." } } finally { semaphore.release() } } }
                                            verifyJobs.joinAll()
                                        } finally { withContext(Dispatchers.Main) { DataCache.aggregatedChannelsCache[channelConfig.id ?: "unknown_category"] = aggregatedChannels.toList(); DataCache.savePersistentCache(context); isReverifying = false } }
                                    }
                                },
                                modifier = Modifier.clip(CircleShape).background(premiumSurface).size(42.dp)
                            ) { if (isReverifying) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = premiumAccent) else Icon(Icons.Default.CheckCircleOutline, contentDescription = "Reverify Links", tint = premiumAccent) }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        IconButton(onClick = { isSearchExpanded = true }, modifier = Modifier.clip(CircleShape).background(premiumSurface).size(42.dp)) { Icon(Icons.Default.Search, "Search", tint = premiumAccent) }
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(onClick = { isFolderView = !isFolderView }, modifier = Modifier.clip(CircleShape).background(premiumSurface).size(42.dp)) { Icon(imageVector = if (isFolderView) Icons.Default.List else Icons.Default.Folder, contentDescription = "Toggle View", tint = premiumAccent) }
                    }
                }
            }

            if (isLoading && aggregatedChannels.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(color = premiumAccent); Spacer(modifier = Modifier.height(16.dp)); Text(statusText, color = premiumTextSec, fontSize = 15.sp, fontWeight = FontWeight.Medium) } }
            } else if (filteredChannels.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No matching channels found.", color = premiumTextSec, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
            } else {
                if (isFolderView) {
                    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                        groupedChannels.forEach { (username, folderChannels) ->
                            val safeName = username.ifEmpty { "Local File" }; val isExpanded = expandedFolders.contains(safeName)
                            item(key = "folder_$safeName") {
                                Card(modifier = Modifier.fillMaxWidth().clickable { expandedFolders = if (isExpanded) expandedFolders - safeName else expandedFolders + safeName }, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = premiumSurface), elevation = CardDefaults.cardElevation(0.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.Folder, contentDescription = "Folder", tint = premiumAccent)
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(text = safeName, color = premiumAccent, fontSize = 17.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(text = "${folderChannels.size} verified", fontSize = 13.sp, color = premiumTextSec, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                            if (isExpanded) { items(folderChannels, key = { "${it.streamUrl}_${it.username}_${it.streamId}" }) { channel -> Box(modifier = Modifier.padding(start = 16.dp, top = 4.dp)) { AggregatedChannelCard(channel, aggregatedChannels, onPlayChannel) } } }
                        }
                    }
                } else {
                    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                        items(filteredChannels, key = { "${it.streamUrl}_${it.username}_${it.streamId}" }) { channel -> AggregatedChannelCard(channel, aggregatedChannels, onPlayChannel) }
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxWidth().background(premiumBg).padding(horizontal = 20.dp, vertical = 12.dp)) {
            Column {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FlatSourceChip(
                        label = "Source 1",
                        tag = "Best",
                        selected = scrapeSource == CatalogSource.BEST,
                        enabled = !isScrapingMore,
                        modifier = Modifier.weight(1f),
                        onTap = {
                            scrapeSource = CatalogSource.BEST; catalogAfter =
                            null; pendingPortals.clear(); attemptedKeys.clear()
                        })
                    FlatSourceChip(
                        label = "Source 2",
                        tag = "Works",
                        selected = scrapeSource == CatalogSource.WORKS,
                        enabled = !isScrapingMore,
                        modifier = Modifier.weight(1f),
                        onTap = {
                            scrapeSource = CatalogSource.WORKS; catalogAfter =
                            null; pendingPortals.clear(); attemptedKeys.clear()
                        })
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (isScrapingMore) { scanJob?.cancel(); isScrapingMore = false; isLoading = false; statusText = "Scan stopped."; return@Button }
                            isScrapingMore = true; isLoading = true
                            scanJob = scope.launch(Dispatchers.IO) {
                                val cacheKey = channelConfig.id ?: "unknown_category"
                                val previouslyScanned: MutableSet<String> = DataCache.scannedPortalsTracker[cacheKey]?.toMutableSet() ?: mutableSetOf()
                                aggregatedChannels.forEach { ch -> previouslyScanned.add("${ch.portalUrl}|${ch.username}") }
                                if (pendingPortals.isEmpty()) {
                                    val prefs = context.getSharedPreferences("DiscoverPrefs", Context.MODE_PRIVATE); val savedJson = prefs.getString("saved_portals", null)
                                    if (savedJson != null) { try { val scraped: List<ScrapedPortal> = Gson().fromJson(savedJson, object : TypeToken<List<ScrapedPortal>>() {}.type); for (p in scraped) { val unifiedKey = "${p.url}|${p.username}"; if (!previouslyScanned.contains(unifiedKey) && !attemptedKeys.contains(unifiedKey)) pendingPortals.add(p) } } catch (e: Exception) {} }
                                    if (pendingPortals.isNotEmpty()) { withContext(Dispatchers.Main) { statusText = "Checking saved portals..." } } else {
                                        withContext(Dispatchers.Main) { statusText = "Searching web for new portals..." }; val page = CatalogScraper.scrapeCatalogPage(scrapeSource, catalogAfter); catalogAfter = page.nextAfter; for (p in page.portals) { val unifiedKey = "${p.url}|${p.username}"; if (!previouslyScanned.contains(unifiedKey) && !attemptedKeys.contains(unifiedKey)) pendingPortals.add(p) }
                                    }
                                }
                                if (pendingPortals.isEmpty()) { withContext(Dispatchers.Main) { statusText = "No more portals found."; isScrapingMore = false; isLoading = false }; return@launch }

                                val toScan = pendingPortals.take(5)
                                IptvVerifier.verifyUntil(
                                    portals = toScan, target = toScan.size,
                                    onAttempted = { p -> val unifiedKey = "${p.url}|${p.username}"; attemptedKeys.add(unifiedKey); previouslyScanned.add(unifiedKey); pendingPortals.removeAll { it.url == p.url && it.username == p.username } },
                                    onProgress = { c, t, a -> statusText = "Verifying portals $c/$t · $a working" },
                                    onAlive = { portal ->
                                        saveDiscoveredPortal(context, portal)
                                        try {
                                            val streamsRes = XtreamApi.service.getLiveStreams(
                                                XtreamApi.formatApiUrl(portal.url), portal.username, portal.pass, null)
                                            if (streamsRes.isSuccessful) {
                                                val allChannels = streamsRes.body() ?: emptyList(); val keywords = (channelConfig.keywords ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }; val excludes = (channelConfig.exclude ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                                                val newHits = allChannels.filter { HardcodedChannels.matches(it.name, keywords, excludes) }.map { ch -> AggregatedChannel(streamId = ch.stream_id, name = ch.name, icon = ch.stream_icon, streamUrl = XtreamApi.buildLiveStreamUrl(portal.url, portal.username, portal.pass, ch.stream_id), sourceName = portal.username, portalUrl = portal.url, username = portal.username, pass = portal.pass) }
                                                val uniqueNewHits = newHits.filter { hit -> aggregatedChannels.none { it.streamUrl == hit.streamUrl } }
                                                if (uniqueNewHits.isNotEmpty()) {
                                                    withContext(Dispatchers.Main) { statusText = "Verifying ${uniqueNewHits.size} channels..." }
                                                    val sem = Semaphore(8)
                                                    val verifyJobs = uniqueNewHits.map { hit -> launch { sem.acquire(); try { if (IptvAliveChecker.isStreamAlive(hit.streamUrl)) { withContext(Dispatchers.Main) { aggregatedChannels.add(hit); DataCache.aggregatedChannelsCache[cacheKey] = aggregatedChannels.toList(); DataCache.savePersistentCache(context) } } } finally { sem.release() } } }
                                                    verifyJobs.joinAll()
                                                }
                                            }
                                        } catch (e: Exception) {}
                                    }
                                )
                                DataCache.scannedPortalsTracker[cacheKey] = previouslyScanned; DataCache.savePersistentCache(context)
                                withContext(Dispatchers.Main) { isScrapingMore = false; isLoading = false; statusText = "Scan Complete" }
                            }
                        },
                        modifier = Modifier.weight(1.5f).height(48.dp), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 4.dp), colors = ButtonDefaults.buttonColors(containerColor = if (isScrapingMore) premiumRed else premiumAccent, contentColor = if (isScrapingMore) premiumAccent else premiumBg)
                    ) {
                        if (isScrapingMore) { Icon(Icons.Default.Stop, null, modifier = Modifier.size(18.dp)); Spacer(modifier = Modifier.width(4.dp)); Text("Stop", fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.SansSerif, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        else { Icon(Icons.Default.TravelExplore, null, modifier = Modifier.size(18.dp)); Spacer(modifier = Modifier.width(4.dp)); Text("Get Channels", fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.SansSerif, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}

@Composable
fun AggregatedChannelCard(channel: AggregatedChannel, allChannels: List<AggregatedChannel>, onPlayChannel: (String, String, List<AggregatedChannel>) -> Unit) {
    val premiumBg = Color(0xFF09090B); val premiumSurface = Color(0xFF18181B); val premiumAccent = Color(0xFFFAFAFA); val premiumTextSec = Color(0xFFA1A1AA)
    Card(onClick = { val orderedSources = listOf(channel) + allChannels.filter { it != channel }; onPlayChannel(channel.streamUrl, channel.name, orderedSources) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = premiumSurface), elevation = CardDefaults.cardElevation(0.dp)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!channel.icon.isNullOrEmpty()) { AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(channel.icon).crossfade(true).build(), contentDescription = channel.name, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(premiumBg), contentScale = ContentScale.Crop) }
            else { Box(modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(premiumBg), contentAlignment = Alignment.Center) { Icon(Icons.Default.Tv, null, tint = premiumTextSec, modifier = Modifier.size(28.dp)) } }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(channel.name, color = premiumAccent, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(2.dp))
                Text(channel.sourceName, color = premiumTextSec, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                HitEpgRow(channel = channel)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.Default.PlayCircle, null, tint = premiumAccent, modifier = Modifier.size(32.dp))
        }
    }
}