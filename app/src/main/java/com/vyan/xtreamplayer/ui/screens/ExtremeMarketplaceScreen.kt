@file:OptIn(ExperimentalMaterial3Api::class)

package com.vyan.xtreamplayer.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayCircleFilled
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
import com.vyan.xtreamplayer.core.ExtremeSourceConfig
import com.vyan.xtreamplayer.core.ExtremeSourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ExtremeMarketplaceScreen(
    onBack: () -> Unit,
    onSourceSelected: (ExtremeSourceConfig) -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }

    var selectedExtremeSources by remember {
        mutableStateOf(sharedPrefs.getStringSet("selected_extreme_sources", emptySet()) ?: emptySet())
    }

    var allSources by remember { mutableStateOf<List<ExtremeSourceConfig>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // PERFECT MEMORY: Remembers category when navigating away
    var selectedCategory by rememberSaveable { mutableStateOf("All") }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            allSources = ExtremeSourceRegistry.loadMasterSources(context)
            withContext(Dispatchers.Main) { isLoading = false }
        }
    }

    val categories = remember(allSources) {
        listOf("All") + allSources.map { it.category.ifBlank { "General" } }.distinct().sorted()
    }

    val filteredSources = remember(allSources, selectedCategory) {
        if (selectedCategory == "All") allSources
        else allSources.filter { it.category.ifBlank { "General" } == selectedCategory }
    }

    val premiumBg = Color(0xFF09090B)
    val premiumSurface = Color(0xFF18181B)
    val premiumAccent = Color(0xFFFAFAFA)
    val premiumTextSec = Color(0xFFA1A1AA)
    val softRed = Color(0xFF881337)

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize().background(premiumBg), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = premiumAccent)
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(premiumBg).statusBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(42.dp).clip(CircleShape).background(premiumSurface)) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = premiumAccent)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Source Marketplace", color = premiumAccent, fontSize = 28.sp, fontWeight = FontWeight.Black)
                    Text("Add or preview community sources", color = premiumTextSec, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(categories) { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
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

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 100.dp)
            ) {
                items(filteredSources) { source ->
                    val isAdded = selectedExtremeSources.contains(source.id)

                    Card(
                        onClick = { onSourceSelected(source) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = premiumSurface),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(60.dp).clip(RoundedCornerShape(12.dp)).background(premiumBg)) {
                                AsyncImage(model = source.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
                                Icon(Icons.Default.PlayCircleFilled, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.align(Alignment.Center).size(24.dp))
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                                Column {
                                    Text(source.name, color = premiumAccent, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(source.category.ifBlank { "General" }, color = premiumTextSec, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Button(
                                    onClick = {
                                        val newSet = if (isAdded) selectedExtremeSources - source.id else selectedExtremeSources + source.id
                                        selectedExtremeSources = newSet
                                        sharedPrefs.edit().putStringSet("selected_extreme_sources", newSet).apply()
                                    },
                                    modifier = Modifier.fillMaxWidth().height(36.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = if (isAdded) Color(0xFF27272A) else softRed, contentColor = premiumAccent),
                                    contentPadding = PaddingValues(0.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(if (isAdded) Icons.Default.CheckCircle else Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (isAdded) "Added to Hub" else "Add to Hub", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}