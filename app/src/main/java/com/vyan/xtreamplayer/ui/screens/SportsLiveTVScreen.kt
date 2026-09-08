@file:OptIn(ExperimentalMaterial3Api::class)

package com.vyan.xtreamplayer.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.vyan.xtreamplayer.sports.LiveSportsEvent
import com.vyan.xtreamplayer.sports.SportsEngine
import com.vyan.xtreamplayer.sports.StreamOption
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

enum class EventStatus { LIVE, UPCOMING, ENDED }

fun parseEventTimestamp(dateTimeStr: String): Long {
    try {
        if (dateTimeStr.isBlank()) return 0L
        val cleanStr = dateTimeStr.replace("\\", "").trim()
        val formats = listOf(
            SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US),
            SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US),
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US),
            SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US)
        )
        for (fmt in formats) {
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            try {
                val parsed = fmt.parse(cleanStr)
                if (parsed != null) return parsed.time
            } catch (_: Exception) {}
        }
    } catch (_: Exception) {}
    return 0L
}

fun getFormattedMatchTime(startTimeStr: String): String {
    val ms = parseEventTimestamp(startTimeStr)
    if (ms == 0L) return ""
    val fmt = SimpleDateFormat("dd MMM, hh:mm a", Locale.US)
    fmt.timeZone = TimeZone.getDefault()
    return fmt.format(ms)
}

fun getEventStatus(event: LiveSportsEvent, serverTimeOffset: Long): EventStatus {
    val startTimeMs = parseEventTimestamp(event.startTime)
    if (startTimeMs == 0L) return EventStatus.LIVE

    val now = System.currentTimeMillis() + serverTimeOffset

    val endTimeMs = if (event.endTime.isNotBlank()) {
        val parsedEnd = parseEventTimestamp(event.endTime)
        if (parsedEnd > 0L) parsedEnd else startTimeMs + (4 * 3600 * 1000L)
    } else {
        startTimeMs + (4 * 3600 * 1000L)
    }

    return when {
        now < startTimeMs -> EventStatus.UPCOMING
        now in startTimeMs..endTimeMs -> EventStatus.LIVE
        else -> EventStatus.ENDED
    }
}

fun getTimeCountdownOrDate(startTimeStr: String, serverTimeOffset: Long): String {
    if (startTimeStr.isBlank()) return ""
    val targetTime = parseEventTimestamp(startTimeStr)
    if (targetTime == 0L) return ""

    val diffMs = targetTime - (System.currentTimeMillis() + serverTimeOffset)
    if (diffMs > 0) {
        val hours = diffMs / (1000 * 60 * 60)
        val minutes = (diffMs / (1000 * 60)) % 60
        val days = hours / 24

        return when {
            days > 0 -> "In $days day${if (days > 1) "s" else ""}"
            hours > 0 -> "In $hours hr ${minutes}m"
            else -> "In $minutes min"
        }
    }
    return "Starting Soon"
}

fun getCategoryIcon(category: String): String {
    return when (category.lowercase(Locale.ROOT).trim()) {
        "boxing", "wwe", "mixed martial arts", "ufc" -> "🥊"
        "tennis", "us open", "wimbledon" -> "🎾"
        "ice hockey", "hockey" -> "🏒"
        "football", "soccer", "ligue 1", "saudi pro league", "coppa italia", "premier league" -> "⚽"
        "motorsport", "motorsports", "f1", "motogp" -> "🏎️"
        "basketball", "nba" -> "🏀"
        "cricket", "european t20 premier league", "caribbean premier league" -> "🏏"
        "baseball", "mlb", "triple-a international league" -> "⚾"
        "rugby" -> "🏉"
        "golf" -> "⛳"
        "volleyball" -> "🏐"
        else -> "📺"
    }
}

@Composable
fun SportsScreen(onPlayMatch: (urlPayload: String, title: String) -> Unit) {
    // Telegram-Style Glass Palette
    val premiumBg = Color(0xFF0E1621)
    val premiumSurface = Color(0xFF17212B)
    val premiumSurfaceVariant = Color(0xFF242F3D)
    val premiumAccent = Color(0xFFFFFFFF)
    val premiumTextSec = Color(0xFF7F91A4)
    val premiumRed = Color(0xFFE53935)
    val premiumBlue = Color(0xFF5288C1)

    val context = LocalContext.current
    var events by remember { mutableStateOf<List<Pair<LiveSportsEvent, EventStatus>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    var refreshTrigger by remember { mutableIntStateOf(0) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedCategory by remember { mutableStateOf("All") }

    var selectedEventForLinks by remember { mutableStateOf<LiveSportsEvent?>(null) }
    var dialogStreamOptions by remember { mutableStateOf<List<StreamOption>>(emptyList()) }
    var isResolvingLinks by remember { mutableStateOf(false) }

    val serverOffset = SportsEngine.serverTimeOffset

    LaunchedEffect(refreshTrigger) {
        isLoading = true
        val rawEvents = SportsEngine.getLiveEvents()

        events = rawEvents
            .map { it to getEventStatus(it, serverOffset) }
            .sortedWith(compareBy(
                { (_, status) ->
                    when (status) {
                        EventStatus.LIVE -> 0
                        EventStatus.UPCOMING -> 1
                        EventStatus.ENDED -> 2
                    }
                },
                { (event, _) -> event.startTime }
            ))
        isLoading = false
    }

    val availableCategories = remember(events) {
        listOf("All") + events.map { it.first.category }.distinct().sorted()
    }

    val filteredEvents = remember(events, selectedTab, selectedCategory) {
        val byTab = when (selectedTab) {
            1 -> events.filter { it.second == EventStatus.LIVE }
            2 -> events.filter { it.second == EventStatus.UPCOMING }
            3 -> events.filter { it.second == EventStatus.ENDED }
            else -> events
        }

        if (selectedCategory == "All") {
            byTab
        } else {
            byTab.filter { it.first.category == selectedCategory }
        }
    }

    val groupedEvents = remember(filteredEvents) {
        filteredEvents.groupBy { it.first.category }.toSortedMap(compareBy { it })
    }

    fun handleEventClick(event: LiveSportsEvent, dynamicTitle: String) {
        scope.launch {
            isResolvingLinks = true
            val slugLinks = if (event.slug.isNotBlank()) SportsEngine.getStreamLinks(event.slug) else emptyList()
            val combined = (slugLinks + event.streamOptions).distinctBy { it.url }

            isResolvingLinks = false

            if (combined.isNotEmpty()) {
                selectedEventForLinks = event.copy(title = dynamicTitle)
                dialogStreamOptions = combined
            } else {
                Toast.makeText(context, "No stream links available for this match yet.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(premiumBg)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Live Sports TV",
                color = premiumAccent,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            IconButton(
                onClick = { refreshTrigger++ },
                modifier = Modifier.clip(CircleShape).background(premiumSurface).size(40.dp)
            ) {
                Icon(Icons.Default.Refresh, "Refresh", tint = premiumTextSec, modifier = Modifier.size(20.dp))
            }
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(availableCategories) { cat ->
                val isSelected = selectedCategory == cat
                val iconStr = if (cat == "All") "🌐" else getCategoryIcon(cat)

                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) premiumBlue else premiumSurface)
                        .clickable { selectedCategory = cat },
                    contentAlignment = Alignment.Center
                ) {
                    Text(iconStr, fontSize = 18.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                val liveCount = events.count { it.second == EventStatus.LIVE && (selectedCategory == "All" || it.first.category == selectedCategory) }
                val upcomingCount = events.count { it.second == EventStatus.UPCOMING && (selectedCategory == "All" || it.first.category == selectedCategory) }
                val recentCount = events.count { it.second == EventStatus.ENDED && (selectedCategory == "All" || it.first.category == selectedCategory) }

                FilterChip(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    label = { Text("All", fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = premiumBlue,
                        selectedLabelColor = premiumAccent,
                        containerColor = premiumSurface,
                        labelColor = premiumTextSec
                    ),
                    border = null,
                    shape = CircleShape
                )
                Spacer(modifier = Modifier.width(4.dp))
                FilterChip(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    label = { Text("Live ($liveCount)", fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = premiumBlue,
                        selectedLabelColor = premiumAccent,
                        containerColor = premiumSurface,
                        labelColor = premiumTextSec
                    ),
                    border = null,
                    shape = CircleShape
                )
                Spacer(modifier = Modifier.width(4.dp))
                FilterChip(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    label = { Text("Upcoming ($upcomingCount)", fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = premiumBlue,
                        selectedLabelColor = premiumAccent,
                        containerColor = premiumSurface,
                        labelColor = premiumTextSec
                    ),
                    border = null,
                    shape = CircleShape
                )
                Spacer(modifier = Modifier.width(4.dp))
                FilterChip(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    label = { Text("Recent ($recentCount)", fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = premiumBlue,
                        selectedLabelColor = premiumAccent,
                        containerColor = premiumSurface,
                        labelColor = premiumTextSec
                    ),
                    border = null,
                    shape = CircleShape
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = premiumBlue)
            }
        } else if (filteredEvents.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No matches found in this section.", color = premiumTextSec, fontWeight = FontWeight.Medium)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                groupedEvents.forEach { (categoryName, categoryEvents) ->
                    if (selectedCategory == "All") {
                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Text(getCategoryIcon(categoryName), fontSize = 15.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(categoryName.uppercase(Locale.ROOT), color = premiumTextSec, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    items(categoryEvents) { (event, status) ->
                        val displayTitle = if (event.teamA.isNotBlank() && event.teamB.isNotBlank()) {
                            if (event.teamA == event.teamB) event.teamA else "${event.teamA} vs ${event.teamB}"
                        } else {
                            event.title
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { handleEventClick(event, displayTitle) },
                            colors = CardDefaults.cardColors(containerColor = premiumSurface),
                            shape = RoundedCornerShape(16.dp),
                            elevation = CardDefaults.cardElevation(0.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = event.title.uppercase(Locale.ROOT),
                                    color = premiumAccent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                val matchTimeDisplay = getFormattedMatchTime(event.startTime)
                                if (matchTimeDisplay.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = matchTimeDisplay,
                                        color = premiumTextSec,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f),
                                        horizontalArrangement = Arrangement.Start
                                    ) {
                                        if (event.logoA.isNotBlank()) {
                                            AsyncImage(
                                                model = event.logoA,
                                                contentDescription = event.teamA,
                                                modifier = Modifier.size(26.dp).clip(CircleShape).background(Color.White)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        Text(
                                            text = event.teamA.ifBlank { event.title },
                                            color = premiumAccent,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Box(
                                        modifier = Modifier.padding(horizontal = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        when (status) {
                                            EventStatus.LIVE -> {
                                                Text(
                                                    text = "Live",
                                                    color = premiumRed,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            EventStatus.UPCOMING -> {
                                                Text(
                                                    text = getTimeCountdownOrDate(event.startTime, serverOffset),
                                                    color = premiumBlue,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    textAlign = TextAlign.Center
                                                )
                                            }
                                            EventStatus.ENDED -> {
                                                Text("ENDED", color = premiumTextSec, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        Text(
                                            text = event.teamB.ifBlank { "" },
                                            color = premiumAccent,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.End
                                        )
                                        if (event.logoB.isNotBlank() && event.teamB.isNotBlank()) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            AsyncImage(
                                                model = event.logoB,
                                                contentDescription = event.teamB,
                                                modifier = Modifier.size(26.dp).clip(CircleShape).background(Color.White)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (isResolvingLinks) {
        Dialog(onDismissRequest = { }) {
            Box(
                modifier = Modifier.size(90.dp).background(premiumSurface, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = premiumBlue)
            }
        }
    }

    if (selectedEventForLinks != null && dialogStreamOptions.isNotEmpty()) {
        Dialog(onDismissRequest = { selectedEventForLinks = null }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = premiumSurface,
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Select Stream Link",
                        color = premiumAccent,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(dialogStreamOptions) { option ->
                            Surface(
                                onClick = {
                                    val currentEvent = selectedEventForLinks
                                    selectedEventForLinks = null
                                    if (currentEvent != null) {
                                        onPlayMatch(option.url, currentEvent.title)
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = premiumSurfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = option.title,
                                    color = premiumAccent,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}