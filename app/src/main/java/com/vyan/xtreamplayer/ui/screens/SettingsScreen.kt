@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package com.vyan.xtreamplayer.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vyan.xtreamplayer.core.ExtremeSourceRegistry
import com.vyan.xtreamplayer.core.ThemePalettes
import com.vyan.xtreamplayer.data.managers.AccountStorageManager
import com.vyan.xtreamplayer.data.managers.SettingsManager
import com.vyan.xtreamplayer.data.managers.UserCustomCategory
import com.vyan.xtreamplayer.data.managers.DataCache
import com.vyan.xtreamplayer.models.AccountType
import com.vyan.xtreamplayer.models.UserAccount
import com.vyan.xtreamplayer.ui.components.CrashLogViewerDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

fun parseCustomCategoryFile(text: String): List<UserCustomCategory> {
    val categories = mutableListOf<UserCustomCategory>()
    val blockRegex = Regex("""HardcodedChannel\s*\((.*?)\)""", RegexOption.DOT_MATCHES_ALL)
    val matches = blockRegex.findAll(text)
    if (matches.count() == 0) throw Exception("Format invalid. Must start with HardcodedChannel(...)")
    matches.forEach { match ->
        val block = match.groupValues[1]
        val idMatch = Regex("""id\s*=\s*"([^"]+)"""").find(block) ?: throw Exception("Missing 'id'")
        val nameMatch = Regex("""name\s*=\s*"([^"]+)"""").find(block) ?: throw Exception("Missing 'name'")
        val keywordsMatch = Regex("""keywords\s*=\s*listOf\((.*?)\)""").find(block) ?: throw Exception("Missing 'keywords'")
        val excludeMatch = Regex("""exclude\s*=\s*listOf\((.*?)\)""").find(block)
        val filtersMatch = Regex("""regions\s*=\s*listOf\((.*?)\)""").find(block) ?: Regex("""filters\s*=\s*listOf\((.*?)\)""").find(block) ?: throw Exception("Missing 'regions' or 'filters'")
        val keywords = keywordsMatch.groupValues[1].split(",").map { it.replace("\"", "").trim() }.filter { it.isNotEmpty() }.joinToString(", ")
        val exclude = excludeMatch?.groupValues?.get(1)?.split(",")?.map { it.replace("\"", "").trim() }?.filter { it.isNotEmpty() }?.joinToString(", ") ?: ""
        val filters = filtersMatch.groupValues[1].split(",").map { it.replace("\"", "").trim() }.filter { it.isNotEmpty() }.joinToString(", ")
        categories.add(
            UserCustomCategory(
                id = idMatch.groupValues[1],
                name = nameMatch.groupValues[1],
                keywords = keywords,
                exclude = exclude,
                filters = filters,
                colorThemeIndex = (0..11).random()
            )
        )
    }
    return categories
}

@Composable
fun SettingsSectionHeader(title: String, icon: ImageVector) {
    val premiumAccent = Color(0xFFFFFFFF)
    val premiumSurface = Color(0xFF17212B)
    val premiumBlue = Color(0xFF5288C1)

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 20.dp, bottom = 10.dp)) {
        Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(premiumSurface), contentAlignment = Alignment.Center) { Icon(icon, null, tint = premiumBlue, modifier = Modifier.size(16.dp)) }
        Spacer(modifier = Modifier.width(12.dp))
        Text(title, color = premiumAccent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SettingsScreen(settingsManager: SettingsManager, accountStorageManager: AccountStorageManager, onAddAccountClick: () -> Unit, onAccountSwitched: () -> Unit) {
    val premiumBg = Color(0xFF0E1621)
    val premiumSurface = Color(0xFF17212B)
    val premiumSurfaceVariant = Color(0xFF242F3D)
    val premiumAccent = Color(0xFFFFFFFF)
    val premiumTextSec = Color(0xFF7F91A4)
    val premiumRed = Color(0xFFE53935)
    val premiumBlue = Color(0xFF5288C1)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val appVersion = remember {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "Unknown"
        } catch (_: Exception) {
            "Unknown"
        }
    }

    var currentSettingsPage by rememberSaveable { mutableStateOf("main") }

    val sharedPrefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
    val initialAppMode = remember { sharedPrefs.getString("active_app_mode", if (settingsManager.isLiveTvAutomatedMode) "advanced" else "basic") ?: "basic" }

    BackHandler(enabled = currentSettingsPage != "main") {
        currentSettingsPage = when (currentSettingsPage) {
            "edit_custom_category" -> "custom_categories"
            "custom_categories" -> "app_mode"
            "edit_profile" -> "profiles"
            "profiles" -> "app_mode"
            "app_mode" -> "main"
            "edit_playlist" -> "playlists"
            "playlists" -> "main"
            else -> "main"
        }
    }

    var editingAccount by remember { mutableStateOf<UserAccount?>(null) }
    var accountsList by remember { mutableStateOf(accountStorageManager.getAccounts()) }
    var editingCustomCategory by remember { mutableStateOf<UserCustomCategory?>(null) }
    var editingProfile by remember { mutableStateOf<com.vyan.xtreamplayer.data.managers.AggregatorProfile?>(null) }
    var showCrashLog by remember { mutableStateOf(false) }

    if (showCrashLog) {
        CrashLogViewerDialog(onDismiss = { showCrashLog = false })
    }

    val flatTextFieldColors = TextFieldDefaults.colors(
        focusedContainerColor = premiumSurfaceVariant,
        unfocusedContainerColor = premiumSurfaceVariant,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        focusedTextColor = premiumAccent,
        unfocusedTextColor = premiumAccent
    )

    when (currentSettingsPage) {
        "main" -> {
            Box(modifier = Modifier.fillMaxSize().background(premiumBg).statusBarsPadding()) {
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    Text("Settings", color = premiumAccent, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
                    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
                        item { SettingsSectionHeader("Manage Playlist", Icons.Default.List) }
                        item {
                            Card(modifier = Modifier.fillMaxWidth().clickable { accountsList = accountStorageManager.getAccounts(); currentSettingsPage = "playlists" }, colors = CardDefaults.cardColors(containerColor = premiumSurface), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(0.dp)) {
                                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Playlist List", color = premiumAccent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Icon(Icons.Default.ChevronRight, null, tint = premiumTextSec)
                                }
                            }
                        }
                        item { Spacer(modifier = Modifier.height(12.dp)) }
                        item { SettingsSectionHeader("General Settings", Icons.Default.Settings) }
                        item {
                            Card(modifier = Modifier.fillMaxWidth().clickable { currentSettingsPage = "app_mode" }, colors = CardDefaults.cardColors(containerColor = premiumSurface), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(0.dp)) {
                                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column {
                                        Text("App Mode", color = premiumAccent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        Spacer(modifier = Modifier.height(2.dp))
                                        val activeMode = sharedPrefs.getString("active_app_mode", if (settingsManager.isLiveTvAutomatedMode) "advanced" else "basic") ?: "basic"
                                        Text(text = when (activeMode) { "unified" -> "Unified Mode Active"; "extreme" -> "Extreme Mode Active"; "sports" -> "Live Sports Mode Active"; "advanced" -> "Advanced Mode Active"; else -> "Basic Mode Active" }, fontSize = 13.sp, color = premiumTextSec, fontWeight = FontWeight.Medium)
                                    }
                                    Icon(Icons.Default.ChevronRight, null, tint = premiumTextSec)
                                }
                            }
                        }
                        item { Spacer(modifier = Modifier.height(12.dp)) }

                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = premiumSurface),
                                shape = RoundedCornerShape(16.dp),
                                elevation = CardDefaults.cardElevation(0.dp)
                            ) {
                                var expandedDecoderMenu by remember { mutableStateOf(false) }
                                val currentDecoder = settingsManager.decoderMode

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { expandedDecoderMenu = true }
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Decoder Engine", color = premiumAccent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = when (currentDecoder) {
                                                "software" -> "Software Only (FFmpeg / CPU)"
                                                "prefer_software" -> "Prefer Software (Fixes Black Screen)"
                                                "hardware" -> "Hardware Only (GPU / Battery Saver)"
                                                else -> "Auto (Hardware with Software Fallback)"
                                            },
                                            fontSize = 13.sp,
                                            color = premiumTextSec,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    Box {
                                        Icon(Icons.Default.Memory, contentDescription = "Decoder", tint = premiumTextSec)
                                        DropdownMenu(
                                            expanded = expandedDecoderMenu,
                                            onDismissRequest = { expandedDecoderMenu = false },
                                            modifier = Modifier.background(premiumSurface)
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Auto (Recommended)", color = premiumAccent, fontWeight = FontWeight.Medium) },
                                                onClick = { settingsManager.decoderMode = "auto"; expandedDecoderMenu = false }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Prefer Software (Fixes Black Screen)", color = premiumAccent, fontWeight = FontWeight.Medium) },
                                                onClick = { settingsManager.decoderMode = "prefer_software"; expandedDecoderMenu = false }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Hardware Only (Battery Saver)", color = premiumAccent, fontWeight = FontWeight.Medium) },
                                                onClick = { settingsManager.decoderMode = "hardware"; expandedDecoderMenu = false }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Software Only", color = premiumAccent, fontWeight = FontWeight.Medium) },
                                                onClick = { settingsManager.decoderMode = "software"; expandedDecoderMenu = false }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        item { Spacer(modifier = Modifier.height(12.dp)) }
                        item {
                            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = premiumSurface), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(0.dp)) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("Appearance and UI", fontWeight = FontWeight.Bold, color = premiumAccent, fontSize = 16.sp, modifier = Modifier.padding(bottom = 16.dp))
                                    var hideMovies by remember { mutableStateOf(accountStorageManager.isMoviesTabHidden()) }
                                    var hideSeries by remember { mutableStateOf(accountStorageManager.isSeriesTabHidden()) }
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text("Hide Movies Tab", color = premiumAccent, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                                        Switch(checked = hideMovies, onCheckedChange = { hideMovies = it; accountStorageManager.setMoviesTabHidden(it); onAccountSwitched() }, colors = SwitchDefaults.colors(checkedThumbColor = premiumAccent, checkedTrackColor = premiumBlue, uncheckedThumbColor = premiumTextSec, uncheckedTrackColor = premiumSurfaceVariant, uncheckedBorderColor = premiumTextSec))
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text("Hide Series Tab", color = premiumAccent, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                                        Switch(checked = hideSeries, onCheckedChange = { hideSeries = it; accountStorageManager.setSeriesTabHidden(it); onAccountSwitched() }, colors = SwitchDefaults.colors(checkedThumbColor = premiumAccent, checkedTrackColor = premiumBlue, uncheckedThumbColor = premiumTextSec, uncheckedTrackColor = premiumSurfaceVariant, uncheckedBorderColor = premiumTextSec))
                                    }
                                }
                            }
                        }
                        item { Spacer(modifier = Modifier.height(12.dp)) }
                        item { SettingsSectionHeader("Developer & Debugging", Icons.Default.BugReport) }
                        item {
                            Card(modifier = Modifier.fillMaxWidth().clickable { showCrashLog = true }, colors = CardDefaults.cardColors(containerColor = premiumSurface), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(0.dp)) {
                                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("View Crash Logs", color = premiumAccent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Icon(Icons.Default.ChevronRight, null, tint = premiumTextSec)
                                }
                            }
                        }
                        item {
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = "XtreamPlayer $appVersion",
                                color = premiumTextSec.copy(alpha = 0.5f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                            )
                        }
                    }
                }
            }
        }
        "app_mode" -> {
            val isVerifiedIndian by rememberSaveable { mutableStateOf(sharedPrefs.getBoolean("is_verified_indian_network", false)) }
            var activeAppMode by rememberSaveable { mutableStateOf(sharedPrefs.getString("active_app_mode", if (settingsManager.isLiveTvAutomatedMode) "advanced" else "basic") ?: "basic") }

            var showModeSheet by remember { mutableStateOf(false) }
            val sheetState = rememberModalBottomSheetState()

            var seenExtreme by rememberSaveable { mutableStateOf(sharedPrefs.getBoolean("seen_mode_extreme", false)) }
            var seenUnified by rememberSaveable { mutableStateOf(sharedPrefs.getBoolean("seen_mode_unified", false)) }

            val hasNewExtreme = !seenExtreme
            val hasNewUnified = isVerifiedIndian && !seenUnified
            val hasAnyNewMode = hasNewExtreme || hasNewUnified

            Box(modifier = Modifier.fillMaxSize().background(premiumBg).statusBarsPadding()) {
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { currentSettingsPage = "main" }, modifier = Modifier.size(42.dp).clip(CircleShape).background(premiumSurface)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = premiumAccent) }
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("App Mode", color = premiumAccent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    }

                    val modes = mutableListOf(
                        Triple("basic", "Basic Mode", "Standard IPTV experience"),
                        Triple("advanced", "Advanced Mode", "Portals & Custom filters"),
                        Triple("extreme", "Extreme Mode", "Python scrapers & DRM"),
                        Triple("sports", "Live Sports Mode", "Native sports schedules & streams")
                    )

                    if (isVerifiedIndian) {
                        modes.add(Triple("unified", "Unified Mode", "All sources merged"))
                    }

                    val currentModeDetails = modes.find { it.first == activeAppMode } ?: modes[0]

                    Card(
                        onClick = {
                            showModeSheet = true
                            if (hasNewExtreme) {
                                seenExtreme = true
                                sharedPrefs.edit().putBoolean("seen_mode_extreme", true).apply()
                            }
                            if (hasNewUnified) {
                                seenUnified = true
                                sharedPrefs.edit().putBoolean("seen_mode_unified", true).apply()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = premiumSurface),
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(0.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(currentModeDetails.second, color = premiumAccent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    if (hasAnyNewMode) {
                                        Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(premiumBlue).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                            Text("NEW", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(currentModeDetails.third, color = premiumTextSec, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Icon(Icons.Default.ChevronRight, null, tint = premiumAccent, modifier = Modifier.size(24.dp))
                        }
                    }

                    if (showModeSheet) {
                        ModalBottomSheet(
                            onDismissRequest = { showModeSheet = false },
                            sheetState = sheetState,
                            containerColor = premiumSurface,
                            dragHandle = { BottomSheetDefaults.DragHandle(color = premiumTextSec) }
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp)
                                    .padding(bottom = 40.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "Select App Mode",
                                    color = premiumAccent,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )

                                modes.forEach { (key, title, subtitle) ->
                                    val isSelected = activeAppMode == key
                                    val isItemNew = (key == "extreme" && hasNewExtreme) || (key == "unified" && hasNewUnified)

                                    Surface(
                                        onClick = {
                                            scope.launch { sheetState.hide() }.invokeOnCompletion {
                                                showModeSheet = false
                                            }
                                            activeAppMode = key
                                            sharedPrefs.edit().putString("active_app_mode", key).apply()
                                            settingsManager.isLiveTvAutomatedMode = (key == "advanced" || key == "extreme" || key == "unified" || key == "sports")

                                            if (key == "extreme") {
                                                seenExtreme = true
                                                sharedPrefs.edit().putBoolean("seen_mode_extreme", true).apply()
                                            } else if (key == "unified") {
                                                seenUnified = true
                                                sharedPrefs.edit().putBoolean("seen_mode_unified", true).apply()
                                            }

                                            onAccountSwitched()
                                        },
                                        shape = RoundedCornerShape(14.dp),
                                        color = if (isSelected) premiumSurfaceVariant else premiumBg,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    Text(title, color = premiumAccent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                                    if (isItemNew) {
                                                        Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(premiumBlue).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                                            Text("NEW", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(subtitle, color = premiumTextSec, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                            }
                                            if (isSelected) {
                                                Icon(Icons.Default.Check, null, tint = premiumBlue, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (activeAppMode == "advanced") {
                        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = premiumSurface), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(0.dp)) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(modifier = Modifier.fillMaxWidth().clickable { currentSettingsPage = "custom_categories" }.padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column {
                                        Text("Manage Custom Categories", color = premiumAccent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text("Build your own dynamic aggregator filters", fontSize = 12.sp, color = premiumTextSec, fontWeight = FontWeight.Medium)
                                    }
                                    Icon(Icons.Default.ChevronRight, null, tint = premiumTextSec)
                                }
                                HorizontalDivider(color = premiumSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                                Row(modifier = Modifier.fillMaxWidth().clickable { currentSettingsPage = "profiles" }.padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column {
                                        Text("Manage Profiles", color = premiumAccent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text("Bind categories to specific playlists", fontSize = 12.sp, color = premiumTextSec, fontWeight = FontWeight.Medium)
                                    }
                                    Icon(Icons.Default.ChevronRight, null, tint = premiumTextSec)
                                }
                            }
                        }
                    } else if (activeAppMode == "extreme") {
                        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = premiumSurface), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(0.dp)) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                var extremeLiveTvBehavior by rememberSaveable { mutableStateOf(sharedPrefs.getString("extreme_livetv_behavior", "extreme") ?: "extreme") }

                                Text("Live TV Tab Behavior", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = premiumAccent)
                                Text("Choose how the standard Live TV tab functions while in Extreme Mode.", fontSize = 12.sp, color = premiumTextSec, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val options = listOf("basic" to "Basic", "advanced" to "Advanced", "extreme" to "Extreme")
                                    options.forEach { (key, label) ->
                                        FilterChip(
                                            selected = extremeLiveTvBehavior == key,
                                            onClick = {
                                                extremeLiveTvBehavior = key
                                                sharedPrefs.edit().putString("extreme_livetv_behavior", key).apply()
                                                onAccountSwitched()
                                            },
                                            label = { Text(label, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif) },
                                            shape = CircleShape,
                                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = premiumBlue, selectedLabelColor = premiumAccent, containerColor = premiumSurfaceVariant, labelColor = premiumTextSec),
                                            border = null
                                        )
                                    }
                                }
                            }
                        }
                    } else if (activeAppMode == "unified") {
                        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = premiumSurface), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(0.dp)) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Unified Mode Preferences", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = premiumAccent)
                                Text("Toggle which sources are actively scanned and merged into the Unified Live TV tab.", fontSize = 12.sp, color = premiumTextSec, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))

                                var searchConcurrencyLimit by rememberSaveable { mutableIntStateOf(sharedPrefs.getInt("unified_search_concurrency_limit", 1)) }
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                        Text("Parallel Search Limit", color = premiumAccent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        Text("Higher limits search faster but may cause UI lag.", color = premiumTextSec, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(premiumSurfaceVariant)
                                    ) {
                                        IconButton(
                                            onClick = {
                                                if (searchConcurrencyLimit > 1) {
                                                    searchConcurrencyLimit--
                                                    sharedPrefs.edit().putInt("unified_search_concurrency_limit", searchConcurrencyLimit).apply()
                                                }
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.Remove, contentDescription = "Decrease", tint = if (searchConcurrencyLimit > 1) premiumAccent else Color.Gray, modifier = Modifier.size(16.dp))
                                        }
                                        Text(
                                            text = searchConcurrencyLimit.toString(),
                                            color = premiumAccent,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.widthIn(min = 20.dp),
                                            textAlign = TextAlign.Center
                                        )
                                        IconButton(
                                            onClick = {
                                                if (searchConcurrencyLimit < 10) {
                                                    searchConcurrencyLimit++
                                                    sharedPrefs.edit().putInt("unified_search_concurrency_limit", searchConcurrencyLimit).apply()
                                                }
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = "Increase", tint = if (searchConcurrencyLimit < 10) premiumAccent else Color.Gray, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))
                                HorizontalDivider(color = premiumSurfaceVariant)
                                Spacer(modifier = Modifier.height(6.dp))

                                var unifiedUsePortals by rememberSaveable { mutableStateOf(sharedPrefs.getBoolean("unified_use_discovered_portals", true)) }
                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Discovered Portals", color = premiumAccent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    Switch(
                                        checked = unifiedUsePortals,
                                        onCheckedChange = {
                                            unifiedUsePortals = it
                                            sharedPrefs.edit().putBoolean("unified_use_discovered_portals", it).apply()
                                            onAccountSwitched()
                                        },
                                        colors = SwitchDefaults.colors(checkedThumbColor = premiumAccent, checkedTrackColor = premiumBlue, uncheckedThumbColor = premiumTextSec, uncheckedTrackColor = premiumSurfaceVariant, uncheckedBorderColor = premiumTextSec)
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))
                                Text("Standard Playlists", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = premiumTextSec)
                                var unifiedDisabledPlaylists by remember { mutableStateOf(sharedPrefs.getStringSet("unified_disabled_playlists", emptySet()) ?: emptySet()) }

                                accountsList.forEachIndexed { index, acc ->
                                    val isEnabled = !unifiedDisabledPlaylists.contains(acc.id)
                                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text(acc.alias.ifEmpty { "Playlist ${index + 1}" }, color = premiumAccent, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                        Switch(
                                            checked = isEnabled,
                                            onCheckedChange = { checked ->
                                                val newSet = if (checked) unifiedDisabledPlaylists - acc.id else unifiedDisabledPlaylists + acc.id
                                                unifiedDisabledPlaylists = newSet
                                                sharedPrefs.edit().putStringSet("unified_disabled_playlists", newSet).apply()
                                                onAccountSwitched()
                                            },
                                            colors = SwitchDefaults.colors(checkedThumbColor = premiumAccent, checkedTrackColor = premiumBlue, uncheckedThumbColor = premiumTextSec, uncheckedTrackColor = premiumSurfaceVariant, uncheckedBorderColor = premiumTextSec)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))
                                Text("Extreme DRM Sources", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = premiumTextSec)
                                var unifiedDisabledExtreme by remember { mutableStateOf(sharedPrefs.getStringSet("unified_disabled_extreme_sources", emptySet()) ?: emptySet()) }

                                ExtremeSourceRegistry.ALL_SOURCES.forEach { source ->
                                    val isEnabled = !unifiedDisabledExtreme.contains(source.id)
                                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text(source.name, color = premiumAccent, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                        Switch(
                                            checked = isEnabled,
                                            onCheckedChange = { checked ->
                                                val newSet = if (checked) unifiedDisabledExtreme - source.id else unifiedDisabledExtreme + source.id
                                                unifiedDisabledExtreme = newSet
                                                sharedPrefs.edit().putStringSet("unified_disabled_extreme_sources", newSet).apply()
                                                onAccountSwitched()
                                            },
                                            colors = SwitchDefaults.colors(checkedThumbColor = premiumAccent, checkedTrackColor = premiumBlue, uncheckedThumbColor = premiumTextSec, uncheckedTrackColor = premiumSurfaceVariant, uncheckedBorderColor = premiumTextSec)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(36.dp))
                }
            }
        }
        "profiles" -> {
            var profilesList by remember { mutableStateOf(settingsManager.getProfiles()) }
            var activeProfileId by remember { mutableStateOf(settingsManager.activeProfileId) }
            Box(modifier = Modifier.fillMaxSize().background(premiumBg).statusBarsPadding()) {
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { currentSettingsPage = "app_mode" }, modifier = Modifier.size(42.dp).clip(CircleShape).background(premiumSurface)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = premiumAccent) }
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Manage Profiles", color = premiumAccent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("Create profiles to bind Custom Categories to specific playlists or free portals.", color = premiumTextSec, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 20.dp))
                    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(profilesList, key = { it.id }) { prof ->
                            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = premiumSurface), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(0.dp)) {
                                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = activeProfileId == prof.id, onClick = { activeProfileId = prof.id; settingsManager.activeProfileId = prof.id; onAccountSwitched() }, colors = RadioButtonDefaults.colors(selectedColor = premiumBlue, unselectedColor = premiumTextSec))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f).clickable { editingProfile = prof; currentSettingsPage = "edit_profile" }) {
                                        Text(prof.name, color = premiumAccent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text("${if (prof.useFreePortals) "Free Portals + " else ""}${prof.playlistIds.size} Playlists • ${if (prof.categoryIds.isEmpty()) "All Categories" else "${prof.categoryIds.size} Categories"}", color = premiumTextSec, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    }
                                    if (prof.id != "default") {
                                        IconButton(onClick = { settingsManager.deleteProfile(prof.id); profilesList = settingsManager.getProfiles(); activeProfileId = settingsManager.activeProfileId }) {
                                            Icon(Icons.Default.Delete, "Delete", tint = premiumRed)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { editingProfile = null; currentSettingsPage = "edit_profile" }, modifier = Modifier.fillMaxWidth().height(56.dp).navigationBarsPadding().padding(bottom = 8.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = premiumBlue, contentColor = premiumAccent)) {
                        Text("Build New Profile", fontWeight = FontWeight.Bold, fontSize = 16.sp, fontFamily = FontFamily.SansSerif)
                    }
                }
            }
        }
        "edit_profile" -> {
            var profileName by rememberSaveable { mutableStateOf(editingProfile?.name ?: "") }
            var useFreePortals by rememberSaveable { mutableStateOf(editingProfile?.useFreePortals ?: true) }
            var selectedPlaylists by rememberSaveable { mutableStateOf(editingProfile?.playlistIds?.toSet() ?: emptySet()) }
            var selectedCategories by rememberSaveable { mutableStateOf(editingProfile?.categoryIds?.toSet() ?: emptySet()) }
            var errorMsg by rememberSaveable { mutableStateOf<String?>(null) }
            val customCategoriesList = remember { settingsManager.getCustomCategories() }

            Box(modifier = Modifier.fillMaxSize().background(premiumBg).statusBarsPadding()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.fillMaxWidth().background(premiumBg).padding(horizontal = 16.dp, vertical = 20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { currentSettingsPage = "profiles" }, modifier = Modifier.size(42.dp).clip(CircleShape).background(premiumSurface)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = premiumAccent) }
                        Text(if (editingProfile == null) "New Profile" else "Edit Profile", color = premiumAccent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        IconButton(
                            onClick = {
                                val newProf = com.vyan.xtreamplayer.data.managers.AggregatorProfile(
                                    id = editingProfile?.id ?: System.currentTimeMillis().toString(),
                                    name = profileName.trim(),
                                    useFreePortals = useFreePortals,
                                    playlistIds = selectedPlaylists.toList(),
                                    categoryIds = selectedCategories.toList()
                                )
                                val currentList = settingsManager.getProfiles().toMutableList()
                                currentList.removeAll { it.id == newProf.id }
                                currentList.add(newProf)
                                settingsManager.saveProfiles(currentList)
                                currentSettingsPage = "profiles"
                                onAccountSwitched()
                            },
                            modifier = Modifier.size(42.dp).clip(CircleShape).background(premiumBlue)
                        ) { Icon(Icons.Default.Check, "Save", tint = premiumAccent) }
                    }
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                        if (errorMsg != null) Text(errorMsg!!, color = premiumRed, modifier = Modifier.padding(bottom = 16.dp), fontWeight = FontWeight.Bold)
                        TextField(value = profileName, onValueChange = { profileName = it }, placeholder = { Text("Profile Name", color = premiumTextSec, fontWeight = FontWeight.Medium) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), singleLine = true, colors = flatTextFieldColors)
                        Spacer(modifier = Modifier.height(24.dp))
                        Text("1. Select Sources (Portals/Playlists)", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = premiumAccent)
                        Spacer(modifier = Modifier.height(12.dp))
                        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = premiumSurface), elevation = CardDefaults.cardElevation(0.dp), shape = RoundedCornerShape(16.dp)) {
                            Row(modifier = Modifier.fillMaxWidth().clickable { useFreePortals = !useFreePortals }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = useFreePortals, onCheckedChange = { useFreePortals = it }, colors = CheckboxDefaults.colors(checkedColor = premiumBlue, checkmarkColor = premiumAccent, uncheckedColor = premiumTextSec))
                                Spacer(modifier = Modifier.width(14.dp))
                                Column {
                                    Text("Free Portals", color = premiumAccent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    Text("Scraped from Discover tab", color = premiumTextSec, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                            accountsList.forEach { acc ->
                                HorizontalDivider(color = premiumSurfaceVariant)
                                Row(modifier = Modifier.fillMaxWidth().clickable { selectedPlaylists = if (selectedPlaylists.contains(acc.id)) selectedPlaylists - acc.id else selectedPlaylists + acc.id }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = selectedPlaylists.contains(acc.id), onCheckedChange = { selectedPlaylists = if (it) selectedPlaylists + acc.id else selectedPlaylists - acc.id }, colors = CheckboxDefaults.colors(checkedColor = premiumBlue, checkmarkColor = premiumAccent, uncheckedColor = premiumTextSec))
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Text(acc.alias.ifEmpty { acc.username }, color = premiumAccent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Text("2. Select Categories", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = premiumAccent)
                        Text("If none selected, ALL categories will be shown.", color = premiumTextSec, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = premiumSurface), elevation = CardDefaults.cardElevation(0.dp), shape = RoundedCornerShape(16.dp)) {
                            customCategoriesList.forEachIndexed { index, cat ->
                                if (index > 0) HorizontalDivider(color = premiumSurfaceVariant)
                                Row(modifier = Modifier.fillMaxWidth().clickable { selectedCategories = if (selectedCategories.contains(cat.id)) selectedCategories - cat.id!! else selectedCategories + cat.id!! }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = selectedCategories.contains(cat.id), onCheckedChange = { if (it) selectedCategories = selectedCategories + cat.id!! else selectedCategories = selectedCategories - cat.id!! }, colors = CheckboxDefaults.colors(checkedColor = premiumBlue, checkmarkColor = premiumAccent, uncheckedColor = premiumTextSec))
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Text(cat.name ?: "Unknown", color = premiumAccent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(36.dp))
                    }
                }
            }
        }
        "custom_categories" -> {
            var customCategoriesList by remember { mutableStateOf(settingsManager.getCustomCategories()) }
            var isEditMode by rememberSaveable { mutableStateOf(false) }
            var selectedCategories by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
            var showResetDialog by remember { mutableStateOf(false) }
            val gridState = rememberLazyGridState()

            BackHandler(enabled = isEditMode) {
                isEditMode = false
                selectedCategories = emptySet()
            }

            Box(modifier = Modifier.fillMaxSize().background(premiumBg).statusBarsPadding()) {
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    if (isEditMode) {
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { isEditMode = false; selectedCategories = emptySet() }, modifier = Modifier.size(42.dp).clip(CircleShape).background(premiumSurface)) { Icon(Icons.Default.Close, "Close", tint = premiumAccent) }
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("${selectedCategories.size} selected", color = premiumAccent, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            if (selectedCategories.size == 1) {
                                val index = customCategoriesList.indexOfFirst { it.id == selectedCategories.first() }
                                if (index > 0) IconButton(onClick = { val mut = customCategoriesList.toMutableList(); val item = mut.removeAt(index); mut.add(index - 1, item); settingsManager.updateCustomCategoriesOrder(mut); customCategoriesList = mut }) { Icon(Icons.Default.KeyboardArrowUp, "Move Up", tint = premiumAccent) }
                                if (index < customCategoriesList.size - 1 && index != -1) IconButton(onClick = { val mut = customCategoriesList.toMutableList(); val item = mut.removeAt(index); mut.add(index + 1, item); settingsManager.updateCustomCategoriesOrder(mut); customCategoriesList = mut }) { Icon(Icons.Default.KeyboardArrowDown, "Move Down", tint = premiumAccent) }
                            }
                            if (selectedCategories.isNotEmpty()) IconButton(onClick = { selectedCategories.forEach { settingsManager.deleteCustomCategory(it) }; customCategoriesList = settingsManager.getCustomCategories(); isEditMode = false; selectedCategories = emptySet(); onAccountSwitched() }) { Icon(Icons.Default.Delete, "Delete", tint = premiumRed) }
                        }
                    } else {
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { currentSettingsPage = "app_mode" }, modifier = Modifier.size(42.dp).clip(CircleShape).background(premiumSurface)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = premiumAccent) }
                                Spacer(modifier = Modifier.width(16.dp))
                                Text("Categories", color = premiumAccent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                            }
                            IconButton(onClick = { showResetDialog = true }) { Icon(Icons.Default.Restore, "Reset", tint = premiumRed) }
                        }
                    }
                    if (customCategoriesList.isEmpty()) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("No custom categories", color = premiumTextSec, fontSize = 16.sp, fontWeight = FontWeight.Medium) }
                    } else {
                        LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 160.dp), state = gridState, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                            items(customCategoriesList, key = { it.id ?: "" }) { cat ->
                                val gradient = ThemePalettes.all.getOrElse(cat.colorThemeIndex ?: 0) { ThemePalettes.all[0] }
                                val isSelected = selectedCategories.contains(cat.id ?: "")
                                Card(
                                    modifier = Modifier.fillMaxWidth().height(130.dp).border(if (isSelected) 3.dp else 0.dp, if (isSelected) premiumBlue else Color.Transparent, RoundedCornerShape(16.dp))
                                        .combinedClickable(onLongClick = { isEditMode = true; if (cat.id != null) selectedCategories = selectedCategories + cat.id }, onClick = { if (isEditMode) { if (cat.id != null) selectedCategories = if (isSelected) selectedCategories - cat.id else selectedCategories + cat.id } else { editingCustomCategory = cat; currentSettingsPage = "edit_custom_category" } }),
                                    colors = CardDefaults.cardColors(containerColor = premiumSurface), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(0.dp)
                                ) {
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        Box(modifier = Modifier.fillMaxWidth().height(6.dp).background(Brush.horizontalGradient(gradient)))
                                        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { if (isEditMode) Icon(if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null, tint = if (isSelected) premiumBlue else premiumTextSec) }
                                            Text(text = cat.name ?: "Category", color = premiumAccent, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
                                            Text(text = cat.filters?.split(",")?.firstOrNull() ?: "Global", color = premiumTextSec, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { editingCustomCategory = null; currentSettingsPage = "edit_custom_category" }, modifier = Modifier.fillMaxWidth().height(56.dp).navigationBarsPadding().padding(bottom = 8.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = premiumBlue, contentColor = premiumAccent)) {
                        Text("Build New Category", fontWeight = FontWeight.Bold, fontSize = 16.sp, fontFamily = FontFamily.SansSerif)
                    }
                }
            }
            if (showResetDialog) {
                AlertDialog(
                    containerColor = premiumSurface, shape = RoundedCornerShape(20.dp), onDismissRequest = { showResetDialog = false },
                    title = { Text("Reset to Defaults?", color = premiumAccent, fontWeight = FontWeight.Bold, fontSize = 20.sp, fontFamily = FontFamily.SansSerif) },
                    text = { Text("This will permanently delete all your custom built categories and restore the original factory list.", color = premiumTextSec, fontWeight = FontWeight.Medium, fontSize = 14.sp) },
                    confirmButton = { Button(onClick = { settingsManager.resetToDefaultCategories(); customCategoriesList = settingsManager.getCustomCategories(); showResetDialog = false; onAccountSwitched() }, colors = ButtonDefaults.buttonColors(containerColor = premiumRed, contentColor = premiumAccent), shape = RoundedCornerShape(12.dp)) { Text("Reset", fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif) } },
                    dismissButton = { TextButton(onClick = { showResetDialog = false }) { Text("Cancel", color = premiumTextSec, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif) } }
                )
            }
        }
        "edit_custom_category" -> {
            val scope = rememberCoroutineScope()
            var catName by rememberSaveable { mutableStateOf(editingCustomCategory?.name ?: "") }
            var catKeywords by rememberSaveable { mutableStateOf(editingCustomCategory?.keywords ?: "") }
            var catExclude by rememberSaveable { mutableStateOf(editingCustomCategory?.exclude ?: "") }
            var catFilters by rememberSaveable { mutableStateOf(editingCustomCategory?.filters ?: "") }
            var selectedColorIndex by rememberSaveable { mutableIntStateOf(editingCustomCategory?.colorThemeIndex ?: 0) }
            var errorMsg by rememberSaveable { mutableStateOf<String?>(null) }
            var successMsg by rememberSaveable { mutableStateOf<String?>(null) }
            val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri != null) {
                    scope.launch(Dispatchers.IO) {
                        try {
                            val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                            val parsed = parseCustomCategoryFile(content)
                            parsed.forEach { settingsManager.addCustomCategory(it) }
                            successMsg = "Successfully imported ${parsed.size} categories!"
                            onAccountSwitched()
                        } catch (e: Exception) {
                            errorMsg = "Import Failed: ${e.message}"
                        }
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize().background(premiumBg).statusBarsPadding()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.fillMaxWidth().background(premiumBg).padding(horizontal = 16.dp, vertical = 20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { currentSettingsPage = "custom_categories" }, modifier = Modifier.size(42.dp).clip(CircleShape).background(premiumSurface)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = premiumAccent) }
                        Text(if (editingCustomCategory == null) "New Category" else "Edit Category", color = premiumAccent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        IconButton(
                            onClick = {
                                if (catName.isBlank() || catKeywords.isBlank() || catFilters.isBlank()) { errorMsg = "Name, Keywords, and Filters are required."; return@IconButton }
                                val newCat = UserCustomCategory(
                                    id = editingCustomCategory?.id ?: System.currentTimeMillis().toString(),
                                    name = catName.trim(),
                                    keywords = catKeywords,
                                    exclude = catExclude,
                                    filters = catFilters,
                                    colorThemeIndex = selectedColorIndex,
                                    isBuiltIn = editingCustomCategory?.isBuiltIn ?: false
                                )
                                settingsManager.updateCustomCategory(newCat)
                                currentSettingsPage = "custom_categories"
                                onAccountSwitched()
                            },
                            modifier = Modifier.size(42.dp).clip(CircleShape).background(premiumBlue)
                        ) { Icon(Icons.Default.Check, "Save", tint = premiumAccent) }
                    }

                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                        Text("Live Preview", color = premiumTextSec, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 12.dp))
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Card(modifier = Modifier.width(180.dp).height(130.dp), colors = CardDefaults.cardColors(containerColor = premiumSurface), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(0.dp)) {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    Box(modifier = Modifier.fillMaxWidth().height(6.dp).background(Brush.horizontalGradient(ThemePalettes.all.getOrElse(selectedColorIndex) { ThemePalettes.all[0] })))
                                    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Icon(Icons.Default.PlayCircleFilled, null, tint = premiumBlue, modifier = Modifier.size(24.dp)) }
                                        Column {
                                            Text(catName.ifEmpty { "Category Name" }, color = premiumAccent, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(catFilters.split(",").firstOrNull()?.ifEmpty { "Filter" } ?: "Filter", color = premiumTextSec, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        if (errorMsg != null) Text(errorMsg!!, color = premiumRed, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
                        if (successMsg != null) Text(successMsg!!, color = Color(0xFF10B981), fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))

                        TextField(value = catName, onValueChange = { catName = it }, placeholder = { Text("Category Name", color = premiumTextSec, fontWeight = FontWeight.Medium) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), singleLine = true, colors = flatTextFieldColors)
                        Spacer(modifier = Modifier.height(12.dp))
                        TextField(value = catFilters, onValueChange = { catFilters = it }, placeholder = { Text("Filters (e.g. US, UK, Sports)", color = premiumTextSec, fontWeight = FontWeight.Medium) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), singleLine = true, colors = flatTextFieldColors)
                        Spacer(modifier = Modifier.height(12.dp))
                        TextField(value = catKeywords, onValueChange = { catKeywords = it }, placeholder = { Text("Keywords (comma separated)", color = premiumTextSec, fontWeight = FontWeight.Medium) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = flatTextFieldColors)
                        Spacer(modifier = Modifier.height(12.dp))
                        TextField(value = catExclude, onValueChange = { catExclude = it }, placeholder = { Text("Exclude Words (Optional)", color = premiumTextSec, fontWeight = FontWeight.Medium) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = flatTextFieldColors)

                        Spacer(modifier = Modifier.height(24.dp))
                        Text("Select Theme Color", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = premiumAccent)
                        Spacer(modifier = Modifier.height(12.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(ThemePalettes.all.size) { idx ->
                                val isSelected = selectedColorIndex == idx
                                Box(
                                    modifier = Modifier.size(48.dp).clip(CircleShape).background(Brush.horizontalGradient(ThemePalettes.all[idx])).border(if (isSelected) 3.dp else 0.dp, if (isSelected) premiumAccent else Color.Transparent, CircleShape).clickable { selectedColorIndex = idx },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(20.dp))
                                }
                            }
                        }

                        if (editingCustomCategory == null) {
                            Spacer(modifier = Modifier.height(36.dp))
                            HorizontalDivider(color = premiumSurfaceVariant)
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(onClick = { errorMsg = null; successMsg = null; filePicker.launch("text/*") }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = premiumSurface, contentColor = premiumAccent)) {
                                Icon(Icons.Default.UploadFile, null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Upload File Format (.txt)", fontWeight = FontWeight.Bold, fontSize = 15.sp, fontFamily = FontFamily.SansSerif)
                            }
                        }
                        Spacer(modifier = Modifier.height(48.dp))
                    }
                }
            }
        }
        "playlists" -> {
            Box(modifier = Modifier.fillMaxSize().background(premiumBg).statusBarsPadding()) {
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { currentSettingsPage = "main" }, modifier = Modifier.size(42.dp).clip(CircleShape).background(premiumSurface)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = premiumAccent) }
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Playlist List", color = premiumAccent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    }

                    if (accountsList.isEmpty()) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("No playlists saved yet", color = premiumTextSec, fontSize = 16.sp, fontWeight = FontWeight.Medium) }
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(items = accountsList, key = { it.id }) { acc ->
                                Card(onClick = { editingAccount = acc; currentSettingsPage = "edit_playlist" }, colors = CardDefaults.cardColors(containerColor = premiumSurface), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(0.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                            Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(premiumBg), contentAlignment = Alignment.Center) { Icon(Icons.Default.List, null, tint = premiumTextSec, modifier = Modifier.size(22.dp)) }
                                            Spacer(modifier = Modifier.width(14.dp))
                                            Column {
                                                Text(acc.alias.ifEmpty { acc.username }, color = premiumAccent, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(if (acc.type == AccountType.XTREAM) acc.url else if (acc.type == AccountType.M3U_URL) acc.url else "Local M3U File", color = premiumTextSec, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                        IconButton(onClick = { DataCache.removePortalData(context, acc.url, acc.username); accountStorageManager.removeAccount(acc); accountsList = accountStorageManager.getAccounts(); onAccountSwitched() }, modifier = Modifier.clip(CircleShape).background(premiumSurfaceVariant)) { Icon(Icons.Default.Delete, "Delete", tint = premiumRed, modifier = Modifier.size(22.dp)) }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { onAddAccountClick() }, modifier = Modifier.fillMaxWidth().height(56.dp).navigationBarsPadding().padding(bottom = 8.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = premiumBlue, contentColor = premiumAccent)) {
                        Text("Add New Playlist", fontWeight = FontWeight.Bold, fontSize = 16.sp, fontFamily = FontFamily.SansSerif)
                    }
                }
            }
        }
        "edit_playlist" -> {
            if (editingAccount != null) {
                var editPass by rememberSaveable { mutableStateOf(editingAccount!!.pass) }
                var editServer by rememberSaveable { mutableStateOf(editingAccount!!.url) }
                var editAlias by rememberSaveable { mutableStateOf(editingAccount!!.alias) }
                var editUsername by rememberSaveable { mutableStateOf(editingAccount!!.username) }
                var showPassword by rememberSaveable { mutableStateOf(false) }

                Box(modifier = Modifier.fillMaxSize().background(premiumBg).statusBarsPadding()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(modifier = Modifier.fillMaxWidth().background(premiumBg).padding(horizontal = 16.dp, vertical = 20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { currentSettingsPage = "playlists" }, modifier = Modifier.size(42.dp).clip(CircleShape).background(premiumSurface)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = premiumAccent) }
                            Text("Edit Playlist", color = premiumAccent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            IconButton(
                                onClick = {
                                    val updatedAccount = editingAccount!!.copy(alias = editAlias, url = editServer, username = editUsername, pass = editPass)
                                    accountStorageManager.updateAccount(updatedAccount)
                                    accountsList = accountStorageManager.getAccounts()
                                    currentSettingsPage = "playlists"
                                    onAccountSwitched()
                                },
                                modifier = Modifier.size(42.dp).clip(CircleShape).background(premiumBlue)
                            ) { Icon(Icons.Default.Check, "Save", tint = premiumAccent) }
                        }
                        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            TextField(value = editAlias, onValueChange = { editAlias = it }, placeholder = { Text("Playlist Alias", color = premiumTextSec, fontWeight = FontWeight.Medium) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = flatTextFieldColors, singleLine = true)
                            Spacer(modifier = Modifier.height(16.dp))
                            if (editingAccount!!.type == AccountType.XTREAM) {
                                TextField(value = editServer, onValueChange = { editServer = it }, placeholder = { Text("Server URL", color = premiumTextSec, fontWeight = FontWeight.Medium) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = flatTextFieldColors, singleLine = true)
                                Spacer(modifier = Modifier.height(16.dp))
                                TextField(value = editUsername, onValueChange = { editUsername = it }, placeholder = { Text("Username", color = premiumTextSec, fontWeight = FontWeight.Medium) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = flatTextFieldColors, singleLine = true)
                                Spacer(modifier = Modifier.height(16.dp))
                                TextField(
                                    value = editPass, onValueChange = { editPass = it }, placeholder = { Text("Password", color = premiumTextSec, fontWeight = FontWeight.Medium) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = flatTextFieldColors, singleLine = true,
                                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = { IconButton(onClick = { showPassword = !showPassword }) { Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "Toggle Password", tint = premiumTextSec) } }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}