@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.vyan.xtreamplayer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vyan.xtreamplayer.data.managers.AccountStorageManager
import com.vyan.xtreamplayer.models.LiveCategory
import com.vyan.xtreamplayer.network.XtreamApi
import kotlinx.coroutines.launch

@Composable
fun HiddenCategoriesScreen(accountStorageManager: AccountStorageManager, onCategoryClick: (LiveCategory) -> Unit, onBack: () -> Unit) {
    // Telegram-Style Glass Palette
    val premiumBg = Color(0xFF0E1621)
    val premiumSurface = Color(0xFF17212B)
    val premiumSurfaceVariant = Color(0xFF242F3D)
    val premiumAccent = Color(0xFFFFFFFF)
    val premiumTextSec = Color(0xFF7F91A4)
    val premiumBlue = Color(0xFF5288C1)

    val activeAccount = accountStorageManager.getActiveAccount(); val scope = rememberCoroutineScope()
    var allCategories by remember { mutableStateOf<List<LiveCategory>>(emptyList()) }; var hiddenCategoryIds by remember { mutableStateOf(accountStorageManager.getHiddenCategories()) }
    var isLoading by remember { mutableStateOf(true) }; var errorMessage by remember { mutableStateOf<String?>(null) }; var searchQuery by rememberSaveable { mutableStateOf("") }

    var isEditMode by rememberSaveable { mutableStateOf(false) }
    var selectedCategoryIds by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }

    BackHandler(enabled = searchQuery.isNotEmpty() || isEditMode) {
        if (isEditMode) {
            isEditMode = false
            selectedCategoryIds = emptySet()
        } else {
            searchQuery = ""
        }
    }

    fun loadCategories() {
        if (activeAccount == null) return
        isLoading = true; errorMessage = null
        scope.launch { try { val response = XtreamApi.service.getLiveCategories(XtreamApi.formatApiUrl(activeAccount.url), activeAccount.username, activeAccount.pass); if (response.isSuccessful && response.body() != null) allCategories = response.body()!! else errorMessage = "Failed to load categories" } catch (e: Exception) { errorMessage = "Connection error: ${e.localizedMessage ?: "Unable to connect"}" } finally { isLoading = false } }
    }
    LaunchedEffect(Unit) { loadCategories() }

    val hiddenCategories = remember(allCategories, hiddenCategoryIds, searchQuery) { val list = allCategories.filter { it.category_id in hiddenCategoryIds }; if (searchQuery.isBlank()) list else list.filter { it.category_name.contains(searchQuery, ignoreCase = true) } }

    Column(modifier = Modifier.fillMaxSize().background(premiumBg).statusBarsPadding().padding(horizontal = 16.dp)) {
        if (isEditMode) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { isEditMode = false; selectedCategoryIds = emptySet() }, modifier = Modifier.size(42.dp).clip(CircleShape).background(premiumSurface)) { Icon(Icons.Default.Close, "Close", tint = premiumAccent) }
                Spacer(modifier = Modifier.width(16.dp))
                Text("${selectedCategoryIds.size} selected", color = premiumAccent, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (selectedCategoryIds.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            selectedCategoryIds.forEach { accountStorageManager.unhideCategory(it) }
                            hiddenCategoryIds = accountStorageManager.getHiddenCategories()
                            isEditMode = false
                            selectedCategoryIds = emptySet()
                        },
                        modifier = Modifier.size(42.dp).clip(CircleShape).background(premiumSurfaceVariant)
                    ) { Icon(Icons.Default.Unarchive, "Unhide Selected", tint = premiumBlue) }
                }
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.clip(CircleShape).background(premiumSurface).size(42.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = premiumAccent) }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Archive", color = premiumAccent, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "${hiddenCategories.size} Hidden Categories", color = premiumTextSec, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
            TextField(
                value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                placeholder = { Text("Search archived category...", color = premiumTextSec, fontSize = 15.sp) }, leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = premiumTextSec) },
                trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, null, tint = premiumAccent) } },
                shape = RoundedCornerShape(16.dp), singleLine = true, colors = TextFieldDefaults.colors(focusedContainerColor = premiumSurfaceVariant, unfocusedContainerColor = premiumSurfaceVariant, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedTextColor = premiumAccent, unfocusedTextColor = premiumAccent)
            )
        }

        if (isLoading) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = premiumBlue) } }
        else if (hiddenCategories.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.Archive, contentDescription = null, tint = premiumTextSec.copy(alpha = 0.5f), modifier = Modifier.size(72.dp)); Spacer(modifier = Modifier.height(16.dp)); Text("No categories in archive", color = premiumAccent, fontSize = 20.sp, fontWeight = FontWeight.Bold) } }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                items(hiddenCategories, key = { it.category_id }) { category ->
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
                        colors = CardDefaults.cardColors(containerColor = if (isSelected) premiumSurfaceVariant else premiumSurface),
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(0.dp)
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (isEditMode) {
                                Icon(if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null, tint = if (isSelected) premiumBlue else premiumTextSec)
                                Spacer(modifier = Modifier.width(16.dp))
                            } else {
                                Box(modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(premiumBg), contentAlignment = Alignment.Center) { Icon(Icons.Default.FolderZip, contentDescription = null, tint = premiumTextSec, modifier = Modifier.size(26.dp)) }
                                Spacer(modifier = Modifier.width(16.dp))
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = category.category_name, color = premiumAccent, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(text = "Tap to view & play", color = premiumTextSec, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                            if (!isEditMode) {
                                IconButton(onClick = { accountStorageManager.unhideCategory(category.category_id); hiddenCategoryIds = accountStorageManager.getHiddenCategories() }, modifier = Modifier.size(42.dp).clip(CircleShape).background(premiumSurfaceVariant)) { Icon(Icons.Default.Unarchive, contentDescription = "Unhide Category", tint = premiumBlue, modifier = Modifier.size(22.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }
}