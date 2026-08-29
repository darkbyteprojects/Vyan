@file:OptIn(ExperimentalMaterial3Api::class)

package com.vyan.xtreamplayer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vyan.xtreamplayer.data.managers.AccountManager
import com.vyan.xtreamplayer.models.AccountType
import com.vyan.xtreamplayer.data.managers.DataCache
import com.vyan.xtreamplayer.models.LiveChannel
import com.vyan.xtreamplayer.models.UserAccount
import com.vyan.xtreamplayer.network.XtreamApi
import kotlinx.coroutines.launch

@Composable
fun ChannelsScreen(categoryName: String, categoryId: String, accountManager: AccountManager, onPlayChannel: (LiveChannel) -> Unit, onBack: () -> Unit, overrideAccount: UserAccount? = null) {
    val premiumBg = Color(0xFF09090B); val premiumSurface = Color(0xFF18181B); val premiumAccent = Color(0xFFFAFAFA); val premiumTextSec = Color(0xFFA1A1AA); val premiumRed = Color(0xFFE50914)
    val activeAccount = overrideAccount ?: accountManager.getActiveAccount(); val scope = rememberCoroutineScope()
    var channels by remember { mutableStateOf<List<LiveChannel>>(emptyList()) }; var isLoading by remember { mutableStateOf(true) }; var errorMessage by remember { mutableStateOf<String?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }; val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    var favoriteChannelIds by remember { mutableStateOf(accountManager.getFavoriteItems("fav_channels")) }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = isSearchExpanded) { isSearchExpanded = false; searchQuery = "" }

    fun loadChannels(forceRefresh: Boolean = false) {
        if (activeAccount == null) return
        val cacheKey = "${activeAccount.id}_$categoryId"
        if (!forceRefresh && DataCache.liveChannels.containsKey(cacheKey)) { channels = DataCache.liveChannels[cacheKey] ?: emptyList(); isLoading = false; return }
        if (activeAccount.type != AccountType.XTREAM) { channels = DataCache.liveChannels[cacheKey] ?: emptyList(); isLoading = false; return }
        isLoading = true; errorMessage = null
        scope.launch {
            try {
                val response = XtreamApi.service.getLiveStreams(XtreamApi.formatApiUrl(activeAccount.url), activeAccount.username, activeAccount.pass, categoryId)
                if (response.isSuccessful && response.body() != null) { val result = response.body()!!; channels = result; DataCache.liveChannels[cacheKey] = result } else { errorMessage = "Failed to load channels (Code ${response.code()})" }
            } catch (e: Exception) { errorMessage = "Connection error: ${e.localizedMessage ?: "Unable to connect"}" } finally { isLoading = false }
        }
    }
    LaunchedEffect(categoryId) { loadChannels() }

    val filteredChannels = remember(channels, searchQuery, favoriteChannelIds) { val list = if (searchQuery.isBlank()) channels else channels.filter { it.name.contains(searchQuery, ignoreCase = true) }; list.sortedWith(compareByDescending { channel -> favoriteChannelIds.contains(channel.stream_id.toString()) }) }

    Column(modifier = Modifier.fillMaxSize().background(premiumBg).statusBarsPadding().padding(horizontal = 20.dp)) {

        Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp).animateContentSize()) {
            if (isSearchExpanded) {
                TextField(
                    value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search channel in $categoryName...", color = premiumTextSec, fontSize = 15.sp) },
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
                        Text(categoryName, color = premiumAccent, fontSize = 24.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${filteredChannels.size} Channels", color = premiumTextSec, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    IconButton(onClick = { isSearchExpanded = true }, modifier = Modifier.size(42.dp).clip(CircleShape).background(premiumSurface)) {
                        Icon(Icons.Default.Search, "Search", tint = premiumAccent)
                    }
                }
            }
        }

        if (isLoading) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = premiumAccent) } }
        else if (errorMessage != null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = premiumRed, modifier = Modifier.size(56.dp)); Spacer(modifier = Modifier.height(12.dp))
                    Text(errorMessage!!, color = premiumRed, fontSize = 16.sp, fontWeight = FontWeight.Bold); Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = { loadChannels(true) }, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = premiumAccent, contentColor = premiumBg)) { Text("Retry", fontWeight = FontWeight.Black, fontFamily = FontFamily.SansSerif) }
                }
            }
        } else if (filteredChannels.isEmpty()) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No channels found", color = premiumTextSec, fontSize = 15.sp, fontWeight = FontWeight.Medium) } }
        else {
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                items(filteredChannels, key = { it.stream_id }) { channel ->
                    Card(onClick = { onPlayChannel(channel) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = premiumSurface), elevation = CardDefaults.cardElevation(0.dp)) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (!channel.stream_icon.isNullOrEmpty()) { AsyncImage(model = channel.stream_icon, contentDescription = channel.name, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(premiumBg), contentScale = ContentScale.Crop) }
                            else { Box(modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(premiumBg), contentAlignment = Alignment.Center) { Icon(Icons.Default.Tv, null, tint = premiumTextSec, modifier = Modifier.size(28.dp)) } }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(channel.name, color = premiumAccent, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("ID: ${channel.stream_id}", color = premiumTextSec, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                            val isFav = favoriteChannelIds.contains(channel.stream_id.toString())
                            IconButton(onClick = { accountManager.toggleFavoriteItem("fav_channels", channel.stream_id.toString()); favoriteChannelIds = accountManager.getFavoriteItems("fav_channels") }, modifier = Modifier.size(42.dp).clip(CircleShape).background(if (isFav) Color(0xFFFFD700).copy(alpha = 0.1f) else Color.Transparent)) {
                                Icon(if (isFav) Icons.Default.Star else Icons.Default.StarBorder, contentDescription = "Favorite", tint = if (isFav) Color(0xFFFFD700) else premiumTextSec)
                            }
                        }
                    }
                }
            }
        }
    }
}