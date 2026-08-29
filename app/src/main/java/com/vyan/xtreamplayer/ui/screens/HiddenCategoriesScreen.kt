@file:OptIn(ExperimentalMaterial3Api::class)

package com.vyan.xtreamplayer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import com.vyan.xtreamplayer.data.managers.AccountManager
import com.vyan.xtreamplayer.models.LiveCategory
import com.vyan.xtreamplayer.network.XtreamApi
import kotlinx.coroutines.launch

@Composable
fun HiddenCategoriesScreen(accountManager: AccountManager, onCategoryClick: (LiveCategory) -> Unit, onBack: () -> Unit) {
    val premiumBg = Color(0xFF09090B); val premiumSurface = Color(0xFF18181B); val premiumAccent = Color(0xFFFAFAFA); val premiumTextSec = Color(0xFFA1A1AA)
    val activeAccount = accountManager.getActiveAccount(); val scope = rememberCoroutineScope()
    var allCategories by remember { mutableStateOf<List<LiveCategory>>(emptyList()) }; var hiddenCategoryIds by remember { mutableStateOf(accountManager.getHiddenCategories()) }
    var isLoading by remember { mutableStateOf(true) }; var errorMessage by remember { mutableStateOf<String?>(null) }; var searchQuery by rememberSaveable { mutableStateOf("") }

    BackHandler(enabled = searchQuery.isNotEmpty()) { searchQuery = "" }

    fun loadCategories() {
        if (activeAccount == null) return
        isLoading = true; errorMessage = null
        scope.launch { try { val response = XtreamApi.service.getLiveCategories(XtreamApi.formatApiUrl(activeAccount.url), activeAccount.username, activeAccount.pass); if (response.isSuccessful && response.body() != null) allCategories = response.body()!! else errorMessage = "Failed to load categories" } catch (e: Exception) { errorMessage = "Connection error: ${e.localizedMessage ?: "Unable to connect"}" } finally { isLoading = false } }
    }
    LaunchedEffect(Unit) { loadCategories() }

    val hiddenCategories = remember(allCategories, hiddenCategoryIds, searchQuery) { val list = allCategories.filter { it.category_id in hiddenCategoryIds }; if (searchQuery.isBlank()) list else list.filter { it.category_name.contains(searchQuery, ignoreCase = true) } }

    Column(modifier = Modifier.fillMaxSize().background(premiumBg).statusBarsPadding().padding(horizontal = 20.dp)) {
        // FIXED: Top Padding reduced to 8.dp
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.clip(CircleShape).background(premiumSurface).size(42.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = premiumAccent) }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Archive", color = premiumAccent, fontSize = 32.sp, fontWeight = FontWeight.Black)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "${hiddenCategories.size} Hidden Categories", color = premiumTextSec, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        TextField(
            value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            placeholder = { Text("Search archived category...", color = premiumTextSec, fontSize = 15.sp) }, leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = premiumTextSec) },
            trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, null, tint = premiumAccent) } },
            shape = CircleShape, singleLine = true, colors = TextFieldDefaults.colors(focusedContainerColor = premiumSurface, unfocusedContainerColor = premiumSurface, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedTextColor = premiumAccent, unfocusedTextColor = premiumAccent)
        )

        if (isLoading) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = premiumAccent) } }
        else if (hiddenCategories.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.Archive, contentDescription = null, tint = premiumTextSec, modifier = Modifier.size(72.dp)); Spacer(modifier = Modifier.height(16.dp)); Text("No categories in archive", color = premiumAccent, fontSize = 20.sp, fontWeight = FontWeight.Black) } }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                items(hiddenCategories, key = { it.category_id }) { category ->
                    Card(
                        onClick = { onCategoryClick(category) }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = premiumSurface), shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(0.dp)
                    ) {
                        Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(premiumBg), contentAlignment = Alignment.Center) { Icon(Icons.Default.FolderZip, contentDescription = null, tint = premiumTextSec, modifier = Modifier.size(28.dp)) }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = category.category_name, color = premiumAccent, fontSize = 18.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(text = "Tap to view & play", color = premiumTextSec, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                            IconButton(onClick = { accountManager.unhideCategory(category.category_id); hiddenCategoryIds = accountManager.getHiddenCategories() }, modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF27272A))) { Icon(Icons.Default.Unarchive, contentDescription = "Unhide Category", tint = premiumAccent, modifier = Modifier.size(24.dp)) }
                        }
                    }
                }
            }
        }
    }
}