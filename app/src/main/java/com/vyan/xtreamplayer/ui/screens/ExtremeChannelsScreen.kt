@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.vyan.xtreamplayer.ui.screens

import android.content.Context
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.vyan.xtreamplayer.core.ExtremeChannel
import com.vyan.xtreamplayer.core.ExtremeSourceConfig
import com.vyan.xtreamplayer.core.ExtremeSourceRegistry
import com.vyan.xtreamplayer.core.SourceType
import com.vyan.xtreamplayer.data.managers.SettingsManager
import com.vyan.xtreamplayer.utils.ExtremeStreamChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import org.json.JSONArray
import kotlinx.coroutines.joinAll

data class ExtremeBrandCategory(
    val id: String,
    val name: String,
    val tag: String,
    val keywords: List<String>,
    val exclude: List<String> = emptyList(),
    val gradient: List<Color>,
    val image: String
)

val ExtremeBrandCategories = listOf(
    ExtremeBrandCategory("sony_network", "Sony Network", "Entertainment & Sports", listOf("sony", "set hd", "sony sab", "sony pal", "sony max", "sony max 2", "sony wah", "sony sports", "ten 1", "ten 2", "ten 3", "ten 4", "ten 5", "sony six", "sony pix", "sony bbc earth", "sony yay", "sony aath", "sony marathi"), emptyList(), listOf(Color(0xFFF59E0B), Color(0xFFDB2777)), "https://upload.wikimedia.org/wikipedia/commons/thumb/c/c5/Sony_LIV_logo.svg/1024px-Sony_LIV_logo.svg.png"),
    ExtremeBrandCategory("star_disney", "Star & Disney", "Hotstar & Broadcast", listOf("star plus", "star+", "star bharat", "star utsav", "star gold", "star gold 2", "star movies", "star sports", "star jalsha", "star pravah", "star vijay", "star maa", "star suvarna", "asianet", "hungama", "disney", "national geographic", "nat geo"), emptyList(), listOf(Color(0xFF7C3AED), Color(0xFF22D3EE)), "https://upload.wikimedia.org/wikipedia/commons/thumb/1/11/Disney%2B_Hotstar_logo.svg/1024px-Disney%2B_Hotstar_logo.svg.png"),
    ExtremeBrandCategory("zee_network", "Zee Network", "Zee5 & Cinema", listOf("zee tv", "&tv", "zee anmol", "zee cinema", "&pictures", "zee bollywood", "zee action", "zee classic", "zee cafe", "zee zest", "zing", "zee marathi", "zee talkies", "zee bangla", "zee telugu", "zee kannada", "zee tamil", "zee keralam", "zee sarthak", "zee punjabi"), emptyList(), listOf(Color(0xFFEC4899), Color(0xFF8B5CF6)), "https://upload.wikimedia.org/wikipedia/commons/thumb/e/ee/Zee_TV_2017_logo.svg/1024px-Zee_TV_2017_logo.svg.png"),
    ExtremeBrandCategory("viacom18_colors", "Colors (Viacom18)", "Jio & Entertainment", listOf("colors", "colors rishtey", "colors cineplex", "colors infinity", "comedy central", "mtv", "mtv beats", "vh1", "nickelodeon", "sonic", "nick jr", "history tv18", "sports18", "sports 18", "colors marathi", "colors bangla", "colors kannada", "colors tamil"), emptyList(), listOf(Color(0xFFEF4444), Color(0xFFF59E0B)), "https://upload.wikimedia.org/wikipedia/commons/thumb/3/30/Colors_TV_logo.svg/1024px-Colors_TV_logo.svg.png"),
    ExtremeBrandCategory("sports_hub", "All Sports Hub", "Cricket, Football & WWE", listOf("sport", "sports", "cricket", "ten 1", "ten 2", "ten 3", "ten 4", "ten 5", "sports18", "star sports", "willow", "prime video", "fancode", "fifa", "icc", "wwe", "ufc", "f1", "formula 1", "motogp", "premier league", "champions league", "eurosport", "dd sports"), emptyList(), listOf(Color(0xFFE50914), Color(0xFFB20710)), "https://upload.wikimedia.org/wikipedia/commons/thumb/1/1e/Star_Sports_logo.svg/1024px-Star_Sports_logo.svg.png"),
    ExtremeBrandCategory("fancode_live", "FanCode Live", "Exclusive Sports Stream", listOf("fancode", "fan code"), emptyList(), listOf(Color(0xFF00E5FF), Color(0xFF00838F)), "https://via.placeholder.com/400x200/16181E/00E5FF?text=FanCode"),
    ExtremeBrandCategory("discovery_warner", "Discovery & Warner", "Infotainment & Kids", listOf("discovery", "animal planet", "tlc", "investigation discovery", "discovery science", "discovery turbo", "cartoon network", "pogo", "discovery kids", "eurosport"), emptyList(), listOf(Color(0xFF14B8A6), Color(0xFF0F766E)), "https://upload.wikimedia.org/wikipedia/commons/thumb/5/5b/Discovery_Channel_2019_logo.svg/1024px-Discovery_Channel_2019_logo.svg.png"),
    ExtremeBrandCategory("sun_tv_network", "Sun TV Network", "South Regional", listOf("sun tv", "ktv", "sun music", "sun news", "chutti tv", "adithya tv", "sun life", "gemini tv", "gemini movies", "gemini music", "gemini comedy", "kushi tv", "udaya tv", "udaya movies", "udaya music", "surya tv", "surya movies", "sun bangla", "sun marathi", "sun neo"), emptyList(), listOf(Color(0xFF1D4ED8), Color(0xFF22C55E)), "https://upload.wikimedia.org/wikipedia/commons/thumb/5/50/JioTV_logo.svg/1024px-JioTV_logo.svg.png"),
    ExtremeBrandCategory("news_networks", "News Networks", "National & World News", listOf("aaj tak", "aajtak", "india today", "abp news", "abp ananda", "abp majha", "ndtv 24x7", "ndtv india", "times now", "mirror now", "et now", "dd news", "dd india", "cnn", "bbc news", "al jazeera", "republic"), emptyList(), listOf(Color(0xFF0EA5E9), Color(0xFF1E293B)), "https://via.placeholder.com/400x200/16181E/00E5FF?text=News+Hub"),
    ExtremeBrandCategory("doordarshan_fta", "Doordarshan & FTA", "National Free-to-Air", listOf("dd national", "dd news", "dd sports", "dd india", "dd kisan", "dd bharati", "dd retro", "dd urdu", "dd bangla", "dd sahyadri", "dd girnar", "dd podhigai", "dd malayalam", "dd saptagiri", "dd punjabi", "dangal", "shemaroo", "goldmines", "b4u"), emptyList(), listOf(Color(0xFF059669), Color(0xFF064E3B)), "https://upload.wikimedia.org/wikipedia/commons/thumb/5/50/JioTV_logo.svg/1024px-JioTV_logo.svg.png")
)

@Composable
fun ExtremeChannelsScreen(
    settingsManager: SettingsManager,
    onPlayExtremeChannel: ((ExtremeChannel) -> Unit)? = null
) {
    val premiumBg = Color(0xFF09090B); val premiumSurface = Color(0xFF18181B); val premiumAccent = Color(0xFFFAFAFA); val premiumTextSec = Color(0xFFA1A1AA); val premiumRed = Color(0xFFE50914)
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var masterConfigs by remember { mutableStateOf<List<ExtremeSourceConfig>>(emptyList()) }
    var customConfigs by remember { mutableStateOf<List<ExtremeSourceConfig>>(emptyList()) }
    var isMasterLoading by remember { mutableStateOf(true) }

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

    val allConfigs = remember(masterConfigs, customConfigs) { masterConfigs + customConfigs }
    val selectedSourceIds = remember(allConfigs) { sharedPrefs.getStringSet("selected_extreme_sources", null) ?: allConfigs.map { it.id }.toSet() }
    val activeConfigs = remember(selectedSourceIds, allConfigs) { allConfigs.filter { selectedSourceIds.contains(it.id) || it.id.startsWith("custom_") }.ifEmpty { allConfigs } }

    var selectedBrand by remember { mutableStateOf<ExtremeBrandCategory?>(null) }
    var selectedCustomSource by remember { mutableStateOf<ExtremeSourceConfig?>(null) }

    var aggregatedChannels by remember { mutableStateOf<List<ExtremeChannel>>(emptyList()) }
    val verifiedChannels = remember { mutableStateListOf<ExtremeChannel>() }

    var isLoading by remember { mutableStateOf(false) }
    var isVerifying by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var fetchErrorMessage by remember { mutableStateOf<String?>(null) }

    var brandSearchQuery by rememberSaveable { mutableStateOf("") }
    var channelSearchQuery by rememberSaveable { mutableStateOf("") }
    var isBrandSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var isChannelSearchExpanded by rememberSaveable { mutableStateOf(false) }

    var scanJob by remember { mutableStateOf<Job?>(null) }
    var verifyJob by remember { mutableStateOf<Job?>(null) }

    fun runVerification(channels: List<ExtremeChannel>) {
        verifyJob?.cancel()
        verifiedChannels.clear()
        isVerifying = true
        verifyJob = scope.launch {
            val sem = Semaphore(6)
            channels.map { ch ->
                launch(Dispatchers.IO) {
                    sem.acquire()
                    try {
                        if (ExtremeStreamChecker.isStreamAlive(ch)) {
                            withContext(Dispatchers.Main) {
                                verifiedChannels.add(ch)
                            }
                        }
                    } finally {
                        sem.release()
                    }
                }
            }.joinAll()
            withContext(Dispatchers.Main) { isVerifying = false }
        }
    }

    fun loadBrandChannels(brand: ExtremeBrandCategory) {
        scanJob?.cancel()
        verifyJob?.cancel()
        aggregatedChannels = emptyList()
        verifiedChannels.clear()
        fetchErrorMessage = null
        isLoading = true
        statusText = "Syncing ${activeConfigs.size} Extreme sources for ${brand.name}..."

        scanJob = scope.launch(Dispatchers.IO) {
            try {
                if (ExtremeHubAggregator.cachedChannels.isEmpty()) {
                    ExtremeHubAggregator.syncSources(activeConfigs)
                }
                val matched = ExtremeHubAggregator.getAggregatedBrand(brand)
                withContext(Dispatchers.Main) {
                    if (selectedBrand?.id == brand.id) {
                        if (matched.isEmpty()) fetchErrorMessage = "No channels found for ${brand.name}."
                        else {
                            aggregatedChannels = matched
                            runVerification(matched)
                        }
                        isLoading = false
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (selectedBrand?.id == brand.id) {
                        fetchErrorMessage = "Error scanning Extreme sources: ${e.message}"
                        isLoading = false
                    }
                }
            }
        }
    }

    fun loadCustomSourceChannels(config: ExtremeSourceConfig) {
        scanJob?.cancel()
        verifyJob?.cancel()
        aggregatedChannels = emptyList()
        verifiedChannels.clear()
        fetchErrorMessage = null
        isLoading = true
        statusText = "Fetching channels from ${config.name}..."

        scanJob = scope.launch(Dispatchers.IO) {
            try {
                val result = ExtremeSourceRegistry.fetchChannels(config)
                withContext(Dispatchers.Main) {
                    if (selectedCustomSource?.id == config.id) {
                        if (result.isEmpty()) fetchErrorMessage = "Source offline or 0 channels."
                        else {
                            aggregatedChannels = result
                            verifiedChannels.addAll(result) // Skip verification for custom sources
                        }
                        isLoading = false
                        isVerifying = false
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (selectedCustomSource?.id == config.id) {
                        fetchErrorMessage = "Network error."
                        isLoading = false
                    }
                }
            }
        }
    }

    BackHandler(enabled = isBrandSearchExpanded || isChannelSearchExpanded || selectedBrand != null || selectedCustomSource != null) {
        if (isChannelSearchExpanded) {
            isChannelSearchExpanded = false
            channelSearchQuery = ""
        } else if (isBrandSearchExpanded) {
            isBrandSearchExpanded = false
            brandSearchQuery = ""
        } else {
            scanJob?.cancel()
            verifyJob?.cancel()
            selectedBrand = null
            selectedCustomSource = null
            aggregatedChannels = emptyList()
            verifiedChannels.clear()
        }
    }

    val filteredBrands = remember(brandSearchQuery) {
        if (brandSearchQuery.isBlank()) ExtremeBrandCategories
        else ExtremeBrandCategories.filter { it.name.contains(brandSearchQuery, ignoreCase = true) || it.tag.contains(brandSearchQuery, ignoreCase = true) }
    }

    val filteredChannels = remember(verifiedChannels.size, channelSearchQuery) {
        verifiedChannels.filter { channelSearchQuery.isBlank() || it.name.contains(channelSearchQuery, ignoreCase = true) }
    }

    Box(modifier = Modifier.fillMaxSize().background(premiumBg).statusBarsPadding()) {
        if (isMasterLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = premiumAccent)
            }
        }
        else if (selectedBrand == null && selectedCustomSource == null) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {

                Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp).animateContentSize()) {
                    if (isBrandSearchExpanded) {
                        TextField(
                            value = brandSearchQuery, onValueChange = { brandSearchQuery = it }, modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Search brand provider...", color = premiumTextSec, fontSize = 15.sp) },
                            leadingIcon = { IconButton(onClick = { isBrandSearchExpanded = false; brandSearchQuery = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = premiumAccent) } },
                            trailingIcon = { if (brandSearchQuery.isNotEmpty()) IconButton(onClick = { brandSearchQuery = "" }) { Icon(Icons.Default.Close, null, tint = premiumAccent) } },
                            shape = CircleShape, singleLine = true,
                            colors = TextFieldDefaults.colors(focusedContainerColor = premiumSurface, unfocusedContainerColor = premiumSurface, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedTextColor = premiumAccent, unfocusedTextColor = premiumAccent)
                        )
                    } else {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Extreme Channels", color = premiumAccent, fontSize = 32.sp, fontWeight = FontWeight.Black)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("${activeConfigs.size} Sources Active", color = premiumTextSec, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                IconButton(onClick = { isBrandSearchExpanded = true }, modifier = Modifier.clip(CircleShape).background(premiumSurface).size(42.dp)) { Icon(Icons.Default.Search, contentDescription = "Search", tint = premiumAccent) }
                                // Removed Add Custom Source and Reload Buttons from here!
                            }
                        }
                    }
                }

                if (filteredBrands.isEmpty() && customConfigs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No provider found", color = premiumTextSec, fontWeight = FontWeight.Medium)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 160.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 40.dp)
                    ) {
                        items(filteredBrands, key = { it.id }) { brand ->
                            Card(
                                onClick = {
                                    channelSearchQuery = ""
                                    isChannelSearchExpanded = false
                                    selectedBrand = brand
                                    loadBrandChannels(brand)
                                },
                                modifier = Modifier.fillMaxWidth().height(140.dp),
                                colors = CardDefaults.cardColors(containerColor = premiumSurface),
                                shape = RoundedCornerShape(20.dp),
                                elevation = CardDefaults.cardElevation(0.dp)
                            ) {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    Box(modifier = Modifier.fillMaxWidth().height(6.dp).background(Brush.horizontalGradient(brand.gradient)))
                                    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            SubcomposeAsyncImage(
                                                model = brand.image,
                                                contentDescription = brand.name,
                                                contentScale = ContentScale.Fit,
                                                modifier = Modifier.size(36.dp),
                                                error = { Icon(Icons.Default.Tv, null, tint = premiumTextSec) }
                                            )
                                            Icon(Icons.Default.PlayCircleFilled, null, tint = premiumAccent, modifier = Modifier.size(28.dp))
                                        }
                                        Column {
                                            Text(brand.name, color = premiumAccent, fontSize = 17.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(brand.tag, color = premiumTextSec, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }

                        items(customConfigs, key = { it.id }) { config ->
                            Card(
                                modifier = Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(20.dp))
                                    .combinedClickable(
                                        onClick = {
                                            channelSearchQuery = ""
                                            isChannelSearchExpanded = false
                                            selectedCustomSource = config
                                            loadCustomSourceChannels(config)
                                        },
                                        onLongClick = { sourceToDelete = config }
                                    ),
                                colors = CardDefaults.cardColors(containerColor = premiumSurface),
                                shape = RoundedCornerShape(20.dp),
                                elevation = CardDefaults.cardElevation(0.dp)
                            ) {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    Box(modifier = Modifier.fillMaxWidth().height(6.dp).background(Color(0xFF3B82F6)))
                                    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.PlayCircleFilled, null, tint = premiumAccent, modifier = Modifier.size(28.dp))
                                        }
                                        Column {
                                            Text(config.name, color = premiumAccent, fontSize = 17.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(config.groupId ?: "Custom Source", color = premiumTextSec, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp).animateContentSize()) {
                    if (isChannelSearchExpanded) {
                        val headerName = selectedBrand?.name ?: selectedCustomSource?.name ?: ""
                        TextField(
                            value = channelSearchQuery, onValueChange = { channelSearchQuery = it }, modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Search $headerName channels...", color = premiumTextSec, fontSize = 15.sp) },
                            leadingIcon = { IconButton(onClick = { isChannelSearchExpanded = false; channelSearchQuery = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = premiumAccent) } },
                            trailingIcon = { if (channelSearchQuery.isNotEmpty()) IconButton(onClick = { channelSearchQuery = "" }) { Icon(Icons.Default.Close, null, tint = premiumAccent) } },
                            shape = CircleShape, singleLine = true,
                            colors = TextFieldDefaults.colors(focusedContainerColor = premiumSurface, unfocusedContainerColor = premiumSurface, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedTextColor = premiumAccent, unfocusedTextColor = premiumAccent)
                        )
                    } else {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    scanJob?.cancel()
                                    verifyJob?.cancel()
                                    selectedBrand = null
                                    selectedCustomSource = null
                                    aggregatedChannels = emptyList()
                                    verifiedChannels.clear()
                                },
                                modifier = Modifier.clip(CircleShape).background(premiumSurface).size(42.dp)
                            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = premiumAccent) }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                val headerName = selectedBrand?.name ?: selectedCustomSource?.name ?: ""
                                Text(headerName, color = premiumAccent, fontSize = 24.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${verifiedChannels.size} / ${aggregatedChannels.size} Channels Verified ${if (isVerifying) "..." else ""}", color = premiumTextSec, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                IconButton(onClick = { isChannelSearchExpanded = true }, modifier = Modifier.size(42.dp).clip(CircleShape).background(premiumSurface)) { Icon(Icons.Default.Search, "Search", tint = premiumAccent) }
                                if (!isLoading && aggregatedChannels.isNotEmpty()) {
                                    if (isVerifying) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(end = 4.dp), strokeWidth = 2.5.dp, color = premiumAccent)
                                    } else {
                                        IconButton(
                                            onClick = {
                                                if (selectedBrand != null) loadBrandChannels(selectedBrand!!)
                                                else if (selectedCustomSource != null) loadCustomSourceChannels(selectedCustomSource!!)
                                            },
                                            modifier = Modifier.clip(CircleShape).background(premiumSurface).size(42.dp)
                                        ) { Icon(Icons.Default.Refresh, "Refresh", tint = premiumAccent) }
                                    }
                                }
                            }
                        }
                    }
                }

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = premiumAccent)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(statusText, color = premiumTextSec, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                } else if (fetchErrorMessage != null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.ErrorOutline, null, tint = premiumRed, modifier = Modifier.size(56.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(fetchErrorMessage!!, color = premiumRed, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                } else if (aggregatedChannels.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No channels match your selected filters.", color = premiumTextSec, fontWeight = FontWeight.Medium)
                    }
                } else if (filteredChannels.isEmpty()) {
                    if (isVerifying) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = premiumAccent)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Hunting for working streams...", color = premiumTextSec, fontWeight = FontWeight.Medium)
                            }
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("All streams appear to be offline or dead.", color = premiumTextSec, fontWeight = FontWeight.Medium)
                        }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                        itemsIndexed(filteredChannels, key = { index, ch -> "${ch.id}_${ch.streamUrl}_$index" }) { _, channel ->
                            Card(
                                onClick = {
                                    val headersMap = mutableMapOf<String, String>()
                                    headersMap.putAll(channel.headers)
                                    if (channel.cookie.isNotBlank() && !headersMap.containsKey("Cookie") && !headersMap.containsKey("cookie")) {
                                        headersMap["Cookie"] = channel.cookie
                                    }
                                    onPlayExtremeChannel?.invoke(channel.copy(headers = headersMap))
                                },
                                colors = CardDefaults.cardColors(containerColor = premiumSurface),
                                shape = RoundedCornerShape(20.dp),
                                elevation = CardDefaults.cardElevation(0.dp)
                            ) {
                                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (channel.logo.isNotBlank()) {
                                        SubcomposeAsyncImage(
                                            model = channel.logo,
                                            contentDescription = null,
                                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(premiumBg),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Box(modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(premiumBg), contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Tv, null, tint = premiumTextSec, modifier = Modifier.size(28.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(channel.name, color = premiumAccent, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(channel.sourceName.ifEmpty { channel.group }, color = premiumTextSec, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                            if (channel.keyId.isNotBlank() || channel.keyId.startsWith("http")) {
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

        // Delete Custom Source Dialog
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