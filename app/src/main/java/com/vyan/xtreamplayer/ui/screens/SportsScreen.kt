@file:OptIn(ExperimentalMaterial3Api::class)

package com.vyan.xtreamplayer.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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

fun parseEventTimestamp(dateStr: String, timeStr: String): Long {
    try {
        if (dateStr.isBlank()) return 0L
        val cleanDate = dateStr.replace("\\", "").trim()
        val cleanTime = if (timeStr.isBlank()) "00:00:00" else timeStr.replace("\\", "").trim()

        val formats = listOf(
            SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US),
            SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US),
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US),
            SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US)
        )
        for (fmt in formats) {
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            val parsed = fmt.parse("$cleanDate $cleanTime")
            if (parsed != null) return parsed.time
        }
    } catch (_: Exception) {}
    return 0L
}

fun getEventStatus(event: LiveSportsEvent, serverTimeOffset: Long): EventStatus {
    val startTimeMs = parseEventTimestamp(event.startTime.substringBefore(" "), event.startTime.substringAfter(" ", ""))
    if (startTimeMs == 0L) return EventStatus.LIVE

    val now = System.currentTimeMillis() + serverTimeOffset
    val endTimeMs = if (event.endTime.isNotBlank()) parseEventTimestamp(event.endTime.substringBefore(" "), event.endTime.substringAfter(" ", "")) else startTimeMs + (4 * 3600 * 1000L) // 4 hours window

    // If current time is past start time + 4 hours, mark as ended
    if (now >= endTimeMs) return EventStatus.ENDED
    // If current time is before start time, it's upcoming
    if (now < startTimeMs) return EventStatus.UPCOMING
    // Otherwise, it is actively running
    return EventStatus.LIVE
}

fun getTimeCountdownOrDate(startTimeStr: String, serverTimeOffset: Long): String {
    if (startTimeStr.isBlank()) return ""
    val targetTime = parseEventTimestamp(startTimeStr.substringBefore(" "), startTimeStr.substringAfter(" ", ""))
    if (targetTime == 0L) return ""

    val diffMs = targetTime - (System.currentTimeMillis() + serverTimeOffset)
    if (diffMs > 0) {
        val hours = diffMs / (1000 * 60 * 60)
        val minutes = (diffMs / (1000 * 60)) % 60
        return if (hours > 24) {
            val days = hours / 24
            val dispFormat = SimpleDateFormat("dd/MM/yyyy", Locale.US)
            dispFormat.format(targetTime)
        } else if (hours > 0) {
            "Starts in $hours hour${if (hours > 1) "s" else ""}"
        } else {
            "Starts in $minutes min"
        }
    }
    return "Starts soon"
}

fun getCategoryIcon(category: String): String {
    return when (category.lowercase(Locale.ROOT).trim()) {
        "boxing", "wwe" -> "🥊"
        "tennis" -> "🎾"
        "ice hockey" -> "🏒"
        "football" -> "⚽"
        "motorsport", "motorsports" -> "🏎️"
        "basketball" -> "🏀"
        "cricket" -> "🏏"
        else -> "📺"
    }
}

@Composable
fun SportsScreen(onPlayMatch: (urlPayload: String, title: String) -> Unit) {
    val context = LocalContext.current
    var events by remember { mutableStateOf<List<Pair<LiveSportsEvent, EventStatus>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    var refreshTrigger by remember { mutableIntStateOf(0) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0: All, 1: Live, 2: Upcoming

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

    val filteredEvents = remember(events, selectedTab) {
        when (selectedTab) {
            1 -> events.filter { it.second == EventStatus.LIVE }
            2 -> events.filter { it.second == EventStatus.UPCOMING }
            else -> events
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

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121318))) {
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Live Sports TV",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black
            )
            IconButton(
                onClick = { refreshTrigger++ },
                modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.1f)).size(38.dp)
            ) {
                Icon(Icons.Default.Refresh, "Refresh", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Filter Tabs (All, Live, Upcoming)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val liveCount = events.count { it.second == EventStatus.LIVE }
            val upcomingCount = events.count { it.second == EventStatus.UPCOMING }

            FilterChip(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                label = { Text("All (${events.size})") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFFE50914),
                    selectedLabelColor = Color.White,
                    containerColor = Color(0xFF1E1E24),
                    labelColor = Color.LightGray
                )
            )
            FilterChip(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                label = { Text("Live ($liveCount)") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFFE50914),
                    selectedLabelColor = Color.White,
                    containerColor = Color(0xFF1E1E24),
                    labelColor = Color.LightGray
                )
            )
            FilterChip(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                label = { Text("Upcoming ($upcomingCount)") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFFE50914),
                    selectedLabelColor = Color.White,
                    containerColor = Color(0xFF1E1E24),
                    labelColor = Color.LightGray
                )
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFFE50914))
            }
        } else if (filteredEvents.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No matches found in this section.", color = Color.Gray)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                groupedEvents.forEach { (categoryName, categoryEvents) ->
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Text(getCategoryIcon(categoryName), fontSize = 16.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(categoryName.uppercase(Locale.ROOT), color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, Color(0xFFFF8A00).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .clickable { handleEventClick(event, displayTitle) },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF181920))
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = event.title.uppercase(Locale.ROOT),
                                    color = Color(0xFFE0E0E0),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Team A Section
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f),
                                        horizontalArrangement = Arrangement.Start
                                    ) {
                                        if (event.logoA.isNotBlank()) {
                                            AsyncImage(
                                                model = event.logoA,
                                                contentDescription = event.teamA,
                                                modifier = Modifier.size(28.dp).clip(CircleShape).background(Color.White)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        Text(
                                            text = event.teamA.ifBlank { event.title },
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    // Status Indicator
                                    Box(
                                        modifier = Modifier.padding(horizontal = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        when (status) {
                                            EventStatus.LIVE -> {
                                                Text(
                                                    text = "Live",
                                                    color = Color(0xFFE50914),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Black
                                                )
                                            }
                                            EventStatus.UPCOMING -> {
                                                Text(
                                                    text = getTimeCountdownOrDate(event.startTime, serverOffset),
                                                    color = Color(0xFF3B82F6),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    textAlign = TextAlign.Center
                                                )
                                            }
                                            EventStatus.ENDED -> {
                                                Text("ENDED", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }

                                    // Team B Section
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        Text(
                                            text = event.teamB.ifBlank { "" },
                                            color = Color.White,
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
                                                modifier = Modifier.size(28.dp).clip(CircleShape).background(Color.White)
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
                modifier = Modifier.size(90.dp).background(Color(0xFF1E1E24), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color(0xFFE50914))
            }
        }
    }

    if (selectedEventForLinks != null && dialogStreamOptions.isNotEmpty()) {
        Dialog(onDismissRequest = { selectedEventForLinks = null }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF1E1E24),
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Select Stream Link",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 14.dp)
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
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFF2D2E38),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = option.title,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
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