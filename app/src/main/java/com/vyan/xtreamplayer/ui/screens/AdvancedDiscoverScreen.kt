@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.vyan.xtreamplayer.ui.screens

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vyan.xtreamplayer.data.managers.AccountStorageManager
import com.vyan.xtreamplayer.models.AccountType
import com.vyan.xtreamplayer.network.CatalogSource
import com.vyan.xtreamplayer.data.managers.DataCache
import com.vyan.xtreamplayer.ui.viewmodels.DiscoverViewModel
import com.vyan.xtreamplayer.network.ScrapedPortal
import com.vyan.xtreamplayer.models.UserAccount
import com.vyan.xtreamplayer.utils.NetworkClient

val portalExpiryCache = ConcurrentHashMap<String, String>()

@Composable
fun DiscoverScreen(viewModel: DiscoverViewModel = viewModel(), accountStorageManager: AccountStorageManager, onPortalUsed: () -> Unit, onPreviewPortal: (ScrapedPortal) -> Unit) {
    val premiumBg = Color(0xFF0E1621); val premiumSurface = Color(0xFF17212B); val premiumSurfaceVariant = Color(0xFF242F3D); val premiumAccent = Color(0xFFFFFFFF); val premiumTextSec = Color(0xFF7F91A4); val premiumRed = Color(0xFFE53935); val premiumBlue = Color(0xFF5288C1)
    val context = LocalContext.current; val clipboard = LocalClipboardManager.current
    val prefs = context.getSharedPreferences("DiscoverPrefs", Context.MODE_PRIVATE)
    val deletedPrefs = context.getSharedPreferences("DeletedPortalsPrefs", Context.MODE_PRIVATE)

    // PERFECT MEMORY: explicitly force saving the scroll index for both lists
    val mainListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val folderListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }; var selectedFolderUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var isEditModeMain by remember { mutableStateOf(false) }; var selectedMainItems by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isEditModeFolder by remember { mutableStateOf(false) }; var selectedFolderItems by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showAddDialog by remember { mutableStateOf(false) }; var portalToAdd by remember { mutableStateOf<ScrapedPortal?>(null) }; var newPlaylistName by remember { mutableStateOf("") }
    var favoritePortals by remember { mutableStateOf(prefs.getStringSet("fav_portals", emptySet()) ?: emptySet()) }

    var localSavedPortals by remember { mutableStateOf<List<ScrapedPortal>>(emptyList()) }
    var hiddenDeletedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var permanentlyDeletedKeys by remember { mutableStateOf(deletedPrefs.getStringSet("deleted_keys", emptySet()) ?: emptySet()) }

    DisposableEffect(context) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPrefs, key -> if (key == "saved_portals") { val savedJson = sharedPrefs.getString("saved_portals", null); if (savedJson != null) { try { localSavedPortals = Gson().fromJson(savedJson, object : TypeToken<List<ScrapedPortal>>() {}.type) ?: emptyList() } catch(e: Exception){} } else localSavedPortals = emptyList() } }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        val initialJson = prefs.getString("saved_portals", null); if (initialJson != null) { try { localSavedPortals = Gson().fromJson(initialJson, object : TypeToken<List<ScrapedPortal>>() {}.type) ?: emptyList() } catch(e: Exception){} }
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    LaunchedEffect(selectedMainItems.size) { if (selectedMainItems.isEmpty()) isEditModeMain = false }; LaunchedEffect(selectedFolderItems.size) { if (selectedFolderItems.isEmpty()) isEditModeFolder = false }

    val isScraping by viewModel.isScraping; val statusText by viewModel.statusText
    val combinedPortals = remember(viewModel.discoveredPortals, localSavedPortals, permanentlyDeletedKeys, hiddenDeletedKeys) {
        (viewModel.discoveredPortals + localSavedPortals)
            .distinctBy { "${it.url}|${it.username}" }
            .filter { !permanentlyDeletedKeys.contains("${it.url}|${it.username}") && !hiddenDeletedKeys.contains("${it.url}|${it.username}") }
    }
    val filteredPortals = if (searchQuery.isBlank()) combinedPortals else combinedPortals.filter { it.url.contains(searchQuery, ignoreCase = true) || it.username.contains(searchQuery, ignoreCase = true) }
    val groupedPortals = filteredPortals.groupBy { it.url }

    BackHandler(enabled = isSearchExpanded || selectedFolderUrl != null || isEditModeMain || isEditModeFolder) {
        if (isEditModeMain) { isEditModeMain = false; selectedMainItems = emptySet() } else if (isEditModeFolder) { isEditModeFolder = false; selectedFolderItems = emptySet() }
        else if (isSearchExpanded) { isSearchExpanded = false; searchQuery = "" } else if (selectedFolderUrl != null) selectedFolderUrl = null
    }

    Box(modifier = Modifier.fillMaxSize().background(premiumBg).statusBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {

            Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp).animateContentSize()) {
                if (isSearchExpanded) {
                    TextField(
                        value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search portals or usernames...", color = premiumTextSec, fontSize = 15.sp) },
                        leadingIcon = { IconButton(onClick = { isSearchExpanded = false; searchQuery = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = premiumTextSec) } },
                        trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, null, tint = premiumTextSec) } },
                        shape = RoundedCornerShape(16.dp), singleLine = true,
                        colors = TextFieldDefaults.colors(focusedContainerColor = premiumSurfaceVariant, unfocusedContainerColor = premiumSurfaceVariant, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedTextColor = premiumAccent, unfocusedTextColor = premiumAccent)
                    )
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Discover", color = premiumAccent, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(statusText, color = premiumTextSec, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { isSearchExpanded = true }, modifier = Modifier.size(42.dp).clip(CircleShape).background(premiumSurface)) {
                            Icon(Icons.Default.Search, "Search", tint = premiumTextSec)
                        }
                    }
                }
            }

            val isEditing = if (selectedFolderUrl == null) isEditModeMain else isEditModeFolder
            if (isEditing && combinedPortals.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(16.dp)).background(premiumSurfaceVariant).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        if (selectedFolderUrl == null) { val allKeys = groupedPortals.map { (u, p) -> if (p.size > 1) "folder_$u" else "portal_${u}|${p.first().username}" }.toSet(); selectedMainItems = if (selectedMainItems.size == allKeys.size) emptySet() else allKeys }
                        else { val currentFolderItems = groupedPortals[selectedFolderUrl]?.map { "${it.url}|${it.username}" }?.toSet() ?: emptySet(); selectedFolderItems = if (selectedFolderItems.size == currentFolderItems.size) emptySet() else currentFolderItems }
                    }) { Text("Select All", color = premiumBlue, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif) }
                    Text("${if (selectedFolderUrl == null) selectedMainItems.size else selectedFolderItems.size} Selected", color = premiumAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif)
                    IconButton(
                        onClick = {
                            val keysToDelete = mutableSetOf<String>()
                            if (selectedFolderUrl == null) {
                                selectedMainItems.forEach { key ->
                                    if (key.startsWith("folder_")) {
                                        groupedPortals[key.removePrefix("folder_")]?.forEach { p -> keysToDelete.add("${p.url}|${p.username}") }
                                    } else if (key.startsWith("portal_")) {
                                        keysToDelete.add(key.removePrefix("portal_"))
                                    }
                                }
                                isEditModeMain = false; selectedMainItems = emptySet()
                            } else {
                                keysToDelete.addAll(selectedFolderItems)
                                val remainingInFolder = groupedPortals[selectedFolderUrl]?.count { !keysToDelete.contains("${it.url}|${it.username}") } ?: 0
                                if (remainingInFolder == 0) { selectedFolderUrl = null }
                                isEditModeFolder = false; selectedFolderItems = emptySet()
                            }

                            hiddenDeletedKeys = hiddenDeletedKeys + keysToDelete
                            val newDeleted = permanentlyDeletedKeys + keysToDelete
                            permanentlyDeletedKeys = newDeleted
                            deletedPrefs.edit().putStringSet("deleted_keys", newDeleted).apply()

                            val currentSaved = localSavedPortals.toMutableList()
                            keysToDelete.forEach { k ->
                                currentSaved.removeAll { "${it.url}|${it.username}" == k }
                                val parts = k.split("|")
                                if (parts.size == 2) {
                                    val url = parts[0]
                                    val user = parts[1]
                                    DataCache.removePortalData(context, url, user)
                                    context.getSharedPreferences("PortalExpiryPrefs", Context.MODE_PRIVATE).edit().remove(k).apply()

                                    DataCache.scannedPortalsTracker.forEach { (catId, set) ->
                                        if (set.contains(k)) {
                                            val newSet = set.toMutableSet()
                                            newSet.remove(k)
                                            DataCache.scannedPortalsTracker[catId] = newSet
                                        }
                                    }
                                    DataCache.aggregatedChannelsCache.forEach { (catId, list) ->
                                        val filteredList = list.filterNot { it.portalUrl == url && it.username == user }
                                        if (filteredList.size != list.size) {
                                            DataCache.aggregatedChannelsCache[catId] = filteredList
                                        }
                                    }
                                }
                            }
                            DataCache.savePersistentCache(context)
                            localSavedPortals = currentSaved.toList()
                            prefs.edit().putString("saved_portals", Gson().toJson(currentSaved)).apply()
                            viewModel.deleteSelected(keysToDelete)
                            Toast.makeText(context, "Deleted Permanently", Toast.LENGTH_SHORT).show()
                        },
                        enabled = if (selectedFolderUrl == null) selectedMainItems.isNotEmpty() else selectedFolderItems.isNotEmpty()
                    ) { Icon(Icons.Default.Delete, contentDescription = "Delete", tint = premiumRed) }
                }
            }

            if (combinedPortals.isEmpty() && !isScraping) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.TravelExplore, null, tint = premiumTextSec.copy(alpha = 0.5f), modifier = Modifier.size(72.dp)); Spacer(modifier = Modifier.height(16.dp))
                        Text("No Portals Scraped Yet", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = premiumAccent); Spacer(modifier = Modifier.height(8.dp))
                        Text("Tap Scan below to start searching.", fontSize = 15.sp, color = premiumTextSec, fontWeight = FontWeight.Medium)
                    }
                }
            } else {
                if (selectedFolderUrl != null) {
                    val folderPortals = groupedPortals[selectedFolderUrl] ?: emptyList()
                    Column(modifier = Modifier.weight(1f)) {
                        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { selectedFolderUrl = null; isEditModeFolder = false; selectedFolderItems = emptySet() }, modifier = Modifier.clip(CircleShape).background(premiumSurface).size(42.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = premiumAccent) }
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(selectedFolderUrl ?: "", color = premiumAccent, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        LazyColumn(state = folderListState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                            items(folderPortals) { portal ->
                                val itemKey = "${portal.url}|${portal.username}"
                                PortalItemCard(
                                    portal = portal, isEditMode = isEditModeFolder, isSelected = selectedFolderItems.contains(itemKey), isFav = favoritePortals.contains(itemKey),
                                    onLongClick = { isEditModeFolder = true; selectedFolderItems = selectedFolderItems + itemKey }, onToggleSelect = { selectedFolderItems = if (selectedFolderItems.contains(itemKey)) selectedFolderItems - itemKey else selectedFolderItems + itemKey },
                                    onAddClick = { portalToAdd = portal; newPlaylistName = portal.username; showAddDialog = true }, onPreviewPortal = { onPreviewPortal(portal) },
                                    onCopyClick = { clipboard.setText(AnnotatedString("${portal.url.replace(Regex("^https?://"), "")}:${portal.username}:${portal.pass}")); Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show() },
                                    onFavClick = { favoritePortals = if (favoritePortals.contains(itemKey)) favoritePortals - itemKey else favoritePortals + itemKey; prefs.edit().putStringSet("fav_portals", favoritePortals).apply() }
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(state = mainListState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                        groupedPortals.forEach { (url, portals) ->
                            if (portals.size > 1) {
                                val itemKey = "folder_$url"; val isSelected = selectedMainItems.contains(itemKey)
                                item { FolderItemCard(url = url, count = portals.size, isEditMode = isEditModeMain, isSelected = isSelected, onLongClick = { isEditModeMain = true; selectedMainItems = selectedMainItems + itemKey }, onClick = { if (isEditModeMain) selectedMainItems = if (isSelected) selectedMainItems - itemKey else selectedMainItems + itemKey else selectedFolderUrl = url }) }
                            } else {
                                val portal = portals.first(); val itemKey = "portal_${portal.url}|${portal.username}"; val isSelected = selectedMainItems.contains(itemKey); val isFav = favoritePortals.contains("${portal.url}|${portal.username}")
                                item {
                                    PortalItemCard(
                                        portal = portal, isEditMode = isEditModeMain, isSelected = isSelected, isFav = isFav,
                                        onLongClick = { isEditModeMain = true; selectedMainItems = selectedMainItems + itemKey }, onToggleSelect = { selectedMainItems = if (isSelected) selectedMainItems - itemKey else selectedMainItems + itemKey },
                                        onAddClick = { portalToAdd = portal; newPlaylistName = portal.username; showAddDialog = true }, onPreviewPortal = { onPreviewPortal(portal) },
                                        onCopyClick = { clipboard.setText(AnnotatedString("${portal.url.replace(Regex("^https?://"), "")}:${portal.username}:${portal.pass}")); Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show() },
                                        onFavClick = { favoritePortals = if (isFav) favoritePortals - "${portal.url}|${portal.username}" else favoritePortals + "${portal.url}|${portal.username}"; prefs.edit().putStringSet("fav_portals", favoritePortals).apply() }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp)) {
                Column {
                    var activeSource by remember { mutableStateOf(viewModel.scrapeSource) }

                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlatSourceChip(
                            label = "Source 1",
                            tag = "Best",
                            selected = activeSource == CatalogSource.BEST,
                            enabled = !isScraping,
                            modifier = Modifier.weight(1f),
                            onTap = {
                                activeSource = CatalogSource.BEST
                                viewModel.setSource(CatalogSource.BEST)
                            }
                        )
                        FlatSourceChip(
                            label = "Source 2",
                            tag = "Works",
                            selected = activeSource == CatalogSource.WORKS,
                            enabled = !isScraping,
                            modifier = Modifier.weight(1f),
                            onTap = {
                                activeSource = CatalogSource.WORKS
                                viewModel.setSource(CatalogSource.WORKS)
                            }
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { if (isScraping) viewModel.stopScraping() else viewModel.scrape() }, modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 4.dp), colors = ButtonDefaults.buttonColors(containerColor = if (isScraping) premiumRed else premiumBlue, contentColor = premiumAccent)) {
                            if (isScraping) { Icon(Icons.Default.Stop, null, modifier = Modifier.size(16.dp)); Spacer(modifier = Modifier.width(4.dp)); Text("Stop", fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            else { Icon(Icons.Default.TravelExplore, null, modifier = Modifier.size(16.dp)); Spacer(modifier = Modifier.width(4.dp)); Text("Scan", fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                        Button(onClick = { if (!isScraping) viewModel.getMore() }, modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 4.dp), colors = ButtonDefaults.buttonColors(containerColor = premiumSurface, contentColor = premiumAccent), enabled = !isScraping) {
                            Icon(Icons.Default.AddCircleOutline, null, modifier = Modifier.size(16.dp)); Spacer(modifier = Modifier.width(4.dp)); Text("More", fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Button(
                            onClick = { if (!isScraping) { context.getSharedPreferences("PortalExpiryPrefs", Context.MODE_PRIVATE).edit().clear().apply(); viewModel.reverifyPortals() } },
                            modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 4.dp), colors = ButtonDefaults.buttonColors(containerColor = premiumSurfaceVariant, contentColor = premiumAccent), enabled = !isScraping && combinedPortals.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp)); Spacer(modifier = Modifier.width(4.dp)); Text("Verify", fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog && portalToAdd != null) {
        AlertDialog(
            containerColor = premiumSurface, shape = RoundedCornerShape(20.dp),
            onDismissRequest = { showAddDialog = false }, title = { Text("Save to Playlists", color = premiumAccent, fontWeight = FontWeight.Bold, fontSize = 20.sp, fontFamily = FontFamily.SansSerif) },
            text = { Column { Text("Enter a name for this portal:", fontSize = 14.sp, color = premiumTextSec, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 12.dp)); TextField(value = newPlaylistName, onValueChange = { newPlaylistName = it }, singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(), colors = TextFieldDefaults.colors(focusedContainerColor = premiumSurfaceVariant, unfocusedContainerColor = premiumSurfaceVariant, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedTextColor = premiumAccent, unfocusedTextColor = premiumAccent)) } },
            confirmButton = { Button(onClick = { accountStorageManager.addAccount(
                UserAccount(
                    id = UUID.randomUUID().toString(),
                    alias = newPlaylistName.ifEmpty { portalToAdd!!.username },
                    username = portalToAdd!!.username,
                    pass = portalToAdd!!.pass,
                    url = portalToAdd!!.url,
                    type = AccountType.XTREAM
                )
            ); showAddDialog = false; Toast.makeText(context, "Added!", Toast.LENGTH_SHORT).show(); onPortalUsed() }, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = premiumBlue, contentColor = premiumAccent)) { Text("Save", fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif) } },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Cancel", color = premiumTextSec, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif) } }
        )
    }
}

@Composable
fun FlatSourceChip(label: String, tag: String, selected: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onTap: () -> Unit) {
    val premiumBg = Color(0xFF0E1621); val premiumSurface = Color(0xFF17212B); val premiumAccent = Color(0xFFFFFFFF); val premiumBlue = Color(0xFF5288C1)
    val bgColor = if (selected) premiumBlue else premiumSurface; val contentColor = if (selected) premiumAccent else premiumAccent; val tagColor = if (selected) premiumAccent.copy(alpha = 0.7f) else premiumAccent.copy(alpha = 0.7f)
    Box(modifier = modifier.alpha(if (enabled) 1f else 0.5f).clip(RoundedCornerShape(12.dp)).background(bgColor).clickable(enabled = enabled, onClick = onTap).padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = contentColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, maxLines = 1)
            Spacer(modifier = Modifier.width(6.dp))
            Text(tag, color = tagColor, fontSize = 10.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.SansSerif, maxLines = 1)
        }
    }
}

@Composable
fun FolderItemCard(url: String, count: Int, isEditMode: Boolean, isSelected: Boolean, onLongClick: () -> Unit, onClick: () -> Unit) {
    val premiumSurface = Color(0xFF17212B); val premiumAccent = Color(0xFFFFFFFF); val premiumTextSec = Color(0xFF7F91A4); val premiumBlue = Color(0xFF5288C1)
    val borderModifier = if (isSelected) Modifier.border(2.dp, premiumBlue, RoundedCornerShape(16.dp)) else Modifier
    Card(modifier = Modifier.fillMaxWidth().then(borderModifier).combinedClickable(onLongClick = { onLongClick() }, onClick = { onClick() }), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = premiumSurface), elevation = CardDefaults.cardElevation(0.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isEditMode) { Icon(imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, contentDescription = null, tint = if (isSelected) premiumBlue else premiumTextSec, modifier = Modifier.padding(end = 16.dp)) }
            else { Icon(imageVector = Icons.Default.Folder, contentDescription = "Folder", tint = premiumBlue, modifier = Modifier.padding(end = 16.dp).size(24.dp)) }
            Column(modifier = Modifier.weight(1f)) {
                Text(url, color = premiumAccent, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(2.dp))
                Text("$count accounts found", color = premiumTextSec, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            if (!isEditMode) Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Open Folder", tint = premiumTextSec, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
fun PortalItemCard(portal: ScrapedPortal, isEditMode: Boolean, isSelected: Boolean, isFav: Boolean, onLongClick: () -> Unit, onToggleSelect: () -> Unit, onAddClick: () -> Unit, onPreviewPortal: () -> Unit, onCopyClick: () -> Unit, onFavClick: () -> Unit) {
    val premiumBg = Color(0xFF0E1621); val premiumSurface = Color(0xFF17212B); val premiumSurfaceVariant = Color(0xFF242F3D); val premiumAccent = Color(0xFFFFFFFF); val premiumTextSec = Color(0xFF7F91A4); val premiumBlue = Color(0xFF5288C1)
    val context = LocalContext.current; val expiryPrefs = remember { context.getSharedPreferences("PortalExpiryPrefs", Context.MODE_PRIVATE) }; val cacheKey = "${portal.url}|${portal.username}"
    var expDate by remember { mutableStateOf(expiryPrefs.getString(cacheKey, null) ?: "Loading...") }; val scope = rememberCoroutineScope()

    LaunchedEffect(cacheKey) {
        if (expiryPrefs.contains(cacheKey)) { val savedState = expiryPrefs.getString(cacheKey, ""); if (savedState != "Loading..." && savedState != "Error" && savedState != "Timeout") return@LaunchedEffect }
        scope.launch(Dispatchers.IO) {
            try {
                var safeUrl = portal.url; if (!safeUrl.startsWith("http")) safeUrl = "http://$safeUrl"; if (safeUrl.endsWith("/")) safeUrl = safeUrl.dropLast(1)
                val response = NetworkClient.defaultClient.newCall(Request.Builder().url("$safeUrl/player_api.php?username=${portal.username}&password=${portal.pass}").build()).execute()
                if (response.isSuccessful) {
                    val userInfo = JSONObject(response.body?.string() ?: "").optJSONObject("user_info")
                    if (userInfo != null) {
                        val exp = userInfo.optString("exp_date", "")
                        val parsedExp = if (exp.isNotEmpty() && exp != "null" && exp != "0") { try { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(exp.toLong() * 1000)) } catch (e: Exception) { "Unlimited" } } else "Unlimited"
                        expiryPrefs.edit().putString(cacheKey, parsedExp).apply(); withContext(Dispatchers.Main) { expDate = parsedExp }
                    } else withContext(Dispatchers.Main) { expDate = "Error" }
                } else withContext(Dispatchers.Main) { expDate = "Offline" }
            } catch (e: Exception) { withContext(Dispatchers.Main) { expDate = "Timeout" } }
        }
    }

    val borderModifier = if (isSelected) Modifier.border(2.dp, premiumBlue, RoundedCornerShape(16.dp)) else Modifier
    Card(modifier = Modifier.fillMaxWidth().then(borderModifier).combinedClickable(onLongClick = { onLongClick() }, onClick = { if (isEditMode) onToggleSelect() else onPreviewPortal() }), colors = CardDefaults.cardColors(containerColor = premiumSurface), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(0.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                if (isEditMode) { Icon(if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, contentDescription = null, tint = if (isSelected) premiumBlue else premiumTextSec, modifier = Modifier.padding(end = 12.dp, top = 4.dp)) }
                Column(modifier = Modifier.weight(1f)) {
                    Text(portal.username, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = premiumAccent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(portal.url, fontSize = 12.sp, color = premiumTextSec, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!isEditMode) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(premiumSurfaceVariant).clickable { onCopyClick() }, contentAlignment = Alignment.Center) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = premiumAccent, modifier = Modifier.size(16.dp)) }
                        Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(if (isFav) Color(0xFFFFD700).copy(alpha = 0.15f) else premiumSurfaceVariant).clickable { onFavClick() }, contentAlignment = Alignment.Center) { Icon(if (isFav) Icons.Default.Star else Icons.Default.StarBorder, contentDescription = "Fav", tint = if (isFav) Color(0xFFFFD700) else premiumAccent, modifier = Modifier.size(18.dp)) }
                        Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(premiumBlue).clickable { onAddClick() }, contentAlignment = Alignment.Center) { Icon(Icons.Default.AddCircleOutline, contentDescription = "Add Playlist", tint = premiumAccent, modifier = Modifier.size(18.dp)) }
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Row(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(premiumSurfaceVariant).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CalendarMonth, null, tint = premiumTextSec, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(expDate, color = premiumTextSec, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}