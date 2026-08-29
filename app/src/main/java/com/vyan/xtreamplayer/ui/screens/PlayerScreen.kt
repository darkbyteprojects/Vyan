@file:OptIn(
    ExperimentalMaterial3Api::class,
    UnstableApi::class
)

package com.vyan.xtreamplayer.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.vyan.xtreamplayer.data.managers.SettingsManager
import com.vyan.xtreamplayer.utils.StreamFormatNormalizer
import kotlinx.coroutines.delay

data class PickerSourceItem(
    val title: String,
    val subtitle: String,
    val typeTag: String,
    val isSelected: Boolean,
    val onClick: () -> Unit
)

fun getQualityTag(height: Int): String {
    return when {
        height >= 2160 -> "UHD"
        height >= 1080 -> "FHD"
        height >= 720 -> "HD"
        height > 0 -> "SD"
        else -> "HD"
    }
}

@Composable
fun QualityBoxBadge(tag: String) {
    Box(
        modifier = Modifier
            .border(1.5.dp, Color.White, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = tag,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black
        )
    }
}

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun PlayerScreen(
    streamUrl: String,
    title: String,
    sources: List<AggregatedChannel> = emptyList(),
    unifiedSources: List<UnifiedSource> = emptyList(),
    isLiveStream: Boolean = true,
    userAgent: String = "",
    cookie: String = "",
    keyId: String = "",
    key: String = "",
    headers: Map<String, String> = emptyMap(),
    settingsManager: SettingsManager? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val isPreview = LocalInspectionMode.current

    val premiumAccent = Color(0xFFFAFAFA)
    val premiumSurface = Color(0xFF18181B)
    val premiumTextSec = Color(0xFFA1A1AA)

    var currentSourceIndex by remember { mutableIntStateOf(0) }

    var currentUrl by remember { mutableStateOf(if (unifiedSources.isNotEmpty()) unifiedSources[0].streamUrl else if (sources.isNotEmpty()) sources[0].streamUrl else streamUrl) }
    var currentUa by remember { mutableStateOf(if (unifiedSources.isNotEmpty()) unifiedSources[0].userAgent else userAgent) }
    var currentCookie by remember { mutableStateOf(if (unifiedSources.isNotEmpty()) unifiedSources[0].cookie else cookie) }
    var currentKeyId by remember { mutableStateOf(if (unifiedSources.isNotEmpty()) unifiedSources[0].keyId else keyId) }
    var currentKey by remember { mutableStateOf(if (unifiedSources.isNotEmpty()) unifiedSources[0].key else key) }
    var currentHeaders by remember { mutableStateOf(if (unifiedSources.isNotEmpty()) unifiedSources[0].headers else headers) }

    var isError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var watchdogMessage by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(true) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var areControlsVisible by remember { mutableStateOf(true) }
    var currentVideoHeight by remember { mutableIntStateOf(0) }
    var selectedQualityKey by remember { mutableStateOf("auto") }

    var showSourcePickerModal by remember { mutableStateOf(false) }
    var showQualityPickerModal by remember { mutableStateOf(false) }
    var showResizeDialog by remember { mutableStateOf(false) }
    var showTrackSelectionDialog by remember { mutableStateOf(false) }
    var showHamburgerMenu by remember { mutableStateOf(false) }
    var currentResizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    var openedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var lastPositionMs by remember { mutableLongStateOf(0L) }
    var lastPositionChangeAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var bufferingSince by remember { mutableStateOf<Long?>(null) }

    var isLiveWindow by remember { mutableStateOf(isLiveStream) }

    BackHandler {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onBack()
    }

    DisposableEffect(Unit) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val trackSelector = remember { if (isPreview) null else DefaultTrackSelector(context) }
    val exoPlayer = remember { if (isPreview) null else ExoPlayer.Builder(context).setTrackSelector(trackSelector!!).build() }

    LaunchedEffect(currentUrl, currentUa, currentCookie, currentKeyId, currentKey, currentHeaders, currentSourceIndex) {
        if (isPreview) return@LaunchedEffect
        isLoading = true; isError = false; watchdogMessage = null
        openedAt = System.currentTimeMillis(); lastPositionMs = 0L; lastPositionChangeAt = System.currentTimeMillis()
        bufferingSince = null

        try {
            val activeUa = currentUa.ifBlank { "OTT Navigator" }

            // 1. Pass raw stream data to the Brain (Normalizer)
            val streamConfig = StreamFormatNormalizer.normalize(
                url = currentUrl,
                keyId = currentKeyId,
                key = currentKey,
                cookie = currentCookie,
                baseHeaders = currentHeaders
            )

            // 2. Configure ExoPlayer with the perfectly formatted proxy data
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(activeUa)
                .setAllowCrossProtocolRedirects(true)
                .setKeepPostFor302Redirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000)
                .setDefaultRequestProperties(streamConfig.headers)

            val mediaSourceFactory = DefaultMediaSourceFactory(context).setDataSourceFactory(httpDataSourceFactory)
            val mediaItemBuilder = MediaItem.Builder()
                .setUri(Uri.parse(streamConfig.proxyStreamUrl))
                .setMimeType(streamConfig.mimeType)

            // 3. Configure DRM (also seamlessly handled by proxy)
            if (streamConfig.drmScheme != null) {
                if (streamConfig.proxyDrmLicenseUrl != null) {
                    mediaItemBuilder.setDrmConfiguration(
                        MediaItem.DrmConfiguration.Builder(streamConfig.drmScheme)
                            .setLicenseUri(streamConfig.proxyDrmLicenseUrl)
                            .setLicenseRequestHeaders(streamConfig.headers)
                            .build()
                    )
                } else if (streamConfig.localJwk != null) {
                    val drmSessionManager = DefaultDrmSessionManager.Builder()
                        .setUuidAndExoMediaDrmProvider(streamConfig.drmScheme, FrameworkMediaDrm.DEFAULT_PROVIDER)
                        .build(LocalMediaDrmCallback(streamConfig.localJwk.toByteArray()))
                    mediaSourceFactory.setDrmSessionManagerProvider { drmSessionManager }
                    mediaItemBuilder.setDrmConfiguration(MediaItem.DrmConfiguration.Builder(streamConfig.drmScheme).build())
                }
            }

            // 4. Play
            val mediaSource = mediaSourceFactory.createMediaSource(mediaItemBuilder.build())
            exoPlayer?.setMediaSource(mediaSource)
            exoPlayer?.prepare()
            exoPlayer?.playWhenReady = true

        } catch (e: Exception) {
            isLoading = false; isError = true; errorMessage = e.message ?: "Failed to initialize player"
        }
    }

    DisposableEffect(exoPlayer) {
        if (exoPlayer == null) return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_TIMELINE_CHANGED) || events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                    val duration = player.duration
                    isLiveWindow = player.isCurrentWindowDynamic || duration == C.TIME_UNSET || duration <= 0L
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> { isLoading = true; if (bufferingSince == null) bufferingSince = System.currentTimeMillis() }
                    Player.STATE_READY -> {
                        isLoading = false; isError = false; bufferingSince = null
                        val newDuration = exoPlayer.duration
                        durationMs = if (newDuration == C.TIME_UNSET) 0L else newDuration
                    }
                    Player.STATE_ENDED -> isLoading = false
                }
            }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height > 0) {
                    currentVideoHeight = videoSize.height
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                val totalSources = if (unifiedSources.isNotEmpty()) unifiedSources.size else sources.size

                if (totalSources > 1 && currentSourceIndex < totalSources - 1) {
                    currentSourceIndex++
                    if (unifiedSources.isNotEmpty()) {
                        val next = unifiedSources[currentSourceIndex]
                        currentUrl = next.streamUrl
                        currentUa = next.userAgent
                        currentCookie = next.cookie
                        currentKeyId = next.keyId
                        currentKey = next.key
                        currentHeaders = next.headers
                    } else {
                        currentUrl = sources[currentSourceIndex].streamUrl
                    }
                    watchdogMessage = "Stream error. Automatically switching to source ${currentSourceIndex + 1}..."
                } else {
                    isLoading = false; isError = true
                    val cause = error.cause
                    errorMessage = when {
                        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "Stream blocked. Server returned HTML instead of video."
                        cause is InvalidResponseCodeException -> "HTTP ${cause.responseCode}: CDN Rejected Request."
                        cause is HttpDataSourceException -> "Network Connection Failed. Stream offline."
                        else -> "Playback Error: ${error.errorCodeName}"
                    }
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(exoPlayer, currentSourceIndex, unifiedSources.size, sources.size, isLiveWindow) {
        if (exoPlayer == null) return@LaunchedEffect
        while (true) {
            delay(1000)
            val now = System.currentTimeMillis()
            val currentPos = exoPlayer.currentPosition
            val isBuffering = exoPlayer.playbackState == Player.STATE_BUFFERING
            val isCurrentlyPlaying = exoPlayer.isPlaying

            if (currentPos != lastPositionMs && !isBuffering) {
                lastPositionMs = currentPos
                lastPositionChangeAt = now
                watchdogMessage = null
            } else if (isCurrentlyPlaying) {
                lastPositionChangeAt = now
            }

            var needsFailover = false; var reason = ""
            if (isBuffering && bufferingSince != null && (now - bufferingSince!! > 12000)) { needsFailover = true; reason = "Buffering timeout" }
            else if (!isLiveWindow && isCurrentlyPlaying && !isBuffering && lastPositionMs > 0 && (now - lastPositionChangeAt > 10000)) { needsFailover = true; reason = "Stream frozen" }
            else if (lastPositionMs == 0L && !isCurrentlyPlaying && (now - openedAt > 15000)) { needsFailover = true; reason = "Connection dropped" }
            else if (isError) { needsFailover = true; reason = "Stream error" }

            val totalSources = if (unifiedSources.isNotEmpty()) unifiedSources.size else sources.size

            if (needsFailover && totalSources > 0) {
                if (currentSourceIndex < totalSources - 1) {
                    currentSourceIndex++
                    watchdogMessage = "$reason. Switching to Source ${currentSourceIndex + 1}..."

                    if (unifiedSources.isNotEmpty()) {
                        val next = unifiedSources[currentSourceIndex]
                        currentUrl = next.streamUrl
                        currentUa = next.userAgent
                        currentCookie = next.cookie
                        currentKeyId = next.keyId
                        currentKey = next.key
                        currentHeaders = next.headers
                    } else {
                        currentUrl = sources[currentSourceIndex].streamUrl
                    }
                    delay(2000)
                } else {
                    if (unifiedSources.isNotEmpty() && com.vyan.xtreamplayer.ui.screens.UnifiedSearchManager.isSearching) {
                        watchdogMessage = "Waiting for background search to find backups..."
                        exoPlayer.pause()
                    } else if (totalSources > 1) {
                        currentSourceIndex = 0
                        watchdogMessage = "All sources failed. Restarting cycle..."

                        if (unifiedSources.isNotEmpty()) {
                            val next = unifiedSources[currentSourceIndex]
                            currentUrl = next.streamUrl
                            currentUa = next.userAgent
                            currentCookie = next.cookie
                            currentKeyId = next.keyId
                            currentKey = next.key
                            currentHeaders = next.headers
                        } else {
                            currentUrl = sources[currentSourceIndex].streamUrl
                        }
                        delay(2000)
                    } else {
                        watchdogMessage = "Stream offline. No backup sources available."
                        exoPlayer.pause()
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        val window = activity?.window
        window?.let { WindowInsetsControllerCompat(it, it.decorView).apply { hide(WindowInsetsCompat.Type.systemBars()); systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE } }
        onDispose { window?.let { WindowInsetsControllerCompat(it, it.decorView).show(WindowInsetsCompat.Type.systemBars()) } }
    }

    LaunchedEffect(exoPlayer) {
        if (exoPlayer == null) return@LaunchedEffect
        while (true) { if (exoPlayer.isPlaying) currentPositionMs = exoPlayer.currentPosition; delay(500) }
    }

    LaunchedEffect(areControlsVisible, showSourcePickerModal, showQualityPickerModal, showResizeDialog, showTrackSelectionDialog, showHamburgerMenu) {
        if (areControlsVisible && !showSourcePickerModal && !showQualityPickerModal && !showResizeDialog && !showTrackSelectionDialog && !showHamburgerMenu) { delay(4500); areControlsVisible = false }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { areControlsVisible = !areControlsVisible }
    ) {
        if (isPreview || exoPlayer == null) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { Icon(Icons.Default.PlayCircle, null, tint = Color.White.copy(alpha = 0.2f), modifier = Modifier.size(72.dp)) }
        } else {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        resizeMode = currentResizeMode
                        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }
                },
                update = { view ->
                    view.resizeMode = currentResizeMode
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (isLoading || watchdogMessage != null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.8f)).padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(strokeWidth = 2.5.dp, color = premiumAccent, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(16.dp))
                    }
                    Text(watchdogMessage ?: "Buffering...", color = premiumAccent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        val totalSourcesCount = if (unifiedSources.isNotEmpty()) unifiedSources.size else sources.size
        if (isError && totalSourcesCount <= 1) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 32.dp)) {
                    Icon(Icons.Default.ErrorOutline, null, tint = Color(0xFFE50914), modifier = Modifier.size(64.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Playback Failed", color = premiumAccent, fontWeight = FontWeight.Black, fontSize = 20.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(errorMessage ?: "Unknown error", color = premiumTextSec, fontSize = 14.sp, textAlign = TextAlign.Center)
                }
            }
        }

        AnimatedVisibility(visible = areControlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxWidth().height(160.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent))))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .displayCutoutPadding()
                        .padding(top = 24.dp, start = 24.dp, end = 24.dp)
                        .align(Alignment.TopCenter),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED; onBack() }, modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.1f)).size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = premiumAccent)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(title, color = premiumAccent, fontWeight = FontWeight.Black, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (sources.isNotEmpty() && unifiedSources.isEmpty()) {
                            Text("Source ${currentSourceIndex + 1} of ${sources.size}", color = premiumTextSec, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        } else if (unifiedSources.isNotEmpty()) {
                            val activeUnifiedSource = unifiedSources.find { it.streamUrl == currentUrl }?.sourceName ?: "Unknown Source"
                            Text("Unified Mode • Source ${currentSourceIndex + 1} of ${unifiedSources.size} • $activeUnifiedSource", color = premiumTextSec, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    if (unifiedSources.isNotEmpty() || sources.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(16.dp))
                        IconButton(onClick = { showSourcePickerModal = true }, modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.1f)).size(48.dp)) {
                            Icon(Icons.Default.List, "Sources", tint = premiumAccent)
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!isLiveWindow) {
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                IconButton(
                                    onClick = { exoPlayer?.let { it.seekTo((it.currentPosition - 10000).coerceAtLeast(0L)) } },
                                    modifier = Modifier.size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f))
                                ) {
                                    Icon(Icons.Default.Replay10, contentDescription = "Rewind 10s", tint = Color.White, modifier = Modifier.size(32.dp))
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }

                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            IconButton(
                                onClick = { if (exoPlayer?.isPlaying == true) exoPlayer?.pause() else exoPlayer?.play() },
                                modifier = Modifier.size(72.dp).clip(CircleShape).background(premiumAccent)
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Play/Pause",
                                    tint = Color.Black,
                                    modifier = Modifier.size(42.dp)
                                )
                            }
                        }

                        if (!isLiveWindow) {
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                IconButton(
                                    onClick = { exoPlayer?.let { it.seekTo((it.currentPosition + 10000).coerceAtMost(durationMs)) } },
                                    modifier = Modifier.size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f))
                                ) {
                                    Icon(Icons.Default.Forward10, contentDescription = "Forward 10s", tint = Color.White, modifier = Modifier.size(32.dp))
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().height(220.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f)))))

                Column(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 32.dp, vertical = 24.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        if (isLiveWindow) {
                            Spacer(modifier = Modifier.weight(1f))
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(CircleShape).background(Color(0xFFE50914).copy(alpha = 0.2f)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFE50914)))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("LIVE", color = Color(0xFFE50914), fontSize = 13.sp, fontWeight = FontWeight.Black)
                            }
                        } else {
                            Text("${currentPositionMs / 60000}:${String.format("%02d", (currentPositionMs / 1000) % 60)}", color = premiumAccent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(if (durationMs > 0) "${durationMs / 60000}:${String.format("%02d", (durationMs / 1000) % 60)}" else "", color = premiumTextSec, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (isLiveWindow) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE50914))
                        )
                    } else {
                        Slider(
                            value = currentPositionMs.toFloat(),
                            onValueChange = { exoPlayer?.seekTo(it.toLong()) },
                            valueRange = 0f..(if(durationMs > 0) durationMs.toFloat() else 100f),
                            colors = SliderDefaults.colors(thumbColor = premiumAccent, activeTrackColor = premiumAccent, inactiveTrackColor = Color.White.copy(alpha = 0.3f))
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Row(modifier = Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box {
                                IconButton(onClick = { showHamburgerMenu = true }, modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.1f)).size(48.dp)) {
                                    Icon(Icons.Default.MoreVert, "More Options", tint = premiumAccent)
                                }
                                DropdownMenu(
                                    expanded = showHamburgerMenu,
                                    onDismissRequest = { showHamburgerMenu = false },
                                    modifier = Modifier.background(premiumSurface)
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Aspect Ratio", color = premiumAccent) },
                                        onClick = { showHamburgerMenu = false; showResizeDialog = true },
                                        leadingIcon = { Icon(Icons.Default.AspectRatio, null, tint = premiumAccent) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Audio & Subtitles", color = premiumAccent) },
                                        onClick = { showHamburgerMenu = false; showTrackSelectionDialog = true },
                                        leadingIcon = { Icon(Icons.Default.Subtitles, null, tint = premiumAccent) }
                                    )
                                }
                            }

                            val liveTag = if (selectedQualityKey == "auto") getQualityTag(currentVideoHeight) else getQualityTag(selectedQualityKey.removeSuffix("p").toIntOrNull() ?: 0)
                            Box(
                                modifier = Modifier
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(Color.White.copy(alpha = 0.1f))
                                    .clickable { showQualityPickerModal = true }
                                    .padding(horizontal = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    QualityBoxBadge(tag = liveTag)
                                    Icon(Icons.Default.ArrowDropDown, null, tint = premiumAccent, modifier = Modifier.size(18.dp))
                                }
                            }

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                IconButton(
                                    onClick = {
                                        activity?.enterPictureInPictureMode(PictureInPictureParams.Builder().build())
                                    },
                                    modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.1f)).size(48.dp)
                                ) {
                                    Icon(Icons.Default.PictureInPicture, "PiP", tint = premiumAccent)
                                }
                            }
                        }

                        Row(modifier = Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            IconButton(onClick = { activity?.requestedOrientation = if (activity?.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }, modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.1f)).size(48.dp)) { Icon(Icons.Default.ScreenRotation, null, tint = premiumAccent) }
                        }
                    }
                }
            }
        }
    }

    if (showQualityPickerModal) {
        QualitySwitcherDialog(
            exoPlayer = exoPlayer,
            trackSelector = trackSelector,
            selectedQualityKey = selectedQualityKey,
            onQualitySelected = { key -> selectedQualityKey = key },
            onDismiss = { showQualityPickerModal = false }
        )
    }

    if (showResizeDialog) {
        ResizeModeDialog(
            currentResizeMode = currentResizeMode,
            onDismiss = { showResizeDialog = false },
            onResizeModeSelected = { mode ->
                currentResizeMode = mode
                showResizeDialog = false
            }
        )
    }

    if (showTrackSelectionDialog && exoPlayer != null) {
        TrackSelectionDialog(
            exoPlayer = exoPlayer,
            onDismiss = { showTrackSelectionDialog = false }
        )
    }

    val pickerItems = remember(sources.size, unifiedSources.size, currentSourceIndex, currentUrl) {
        if (unifiedSources.isNotEmpty()) {
            unifiedSources.mapIndexed { index, source ->
                PickerSourceItem(
                    title = source.sourceName,
                    subtitle = source.originalChannelName,
                    typeTag = if (source.sourceType == "EXTREME") "EXTREME" else "ADVANCED",
                    isSelected = source.streamUrl == currentUrl,
                    onClick = {
                        currentSourceIndex = index
                        currentUrl = source.streamUrl
                        currentUa = source.userAgent
                        currentCookie = source.cookie
                        currentKeyId = source.keyId
                        currentKey = source.key
                        currentHeaders = source.headers
                        showSourcePickerModal = false
                    }
                )
            }
        } else if (sources.isNotEmpty()) {
            sources.mapIndexed { index, source ->
                PickerSourceItem(
                    title = source.sourceName,
                    subtitle = source.streamUrl,
                    typeTag = "ADVANCED",
                    isSelected = index == currentSourceIndex,
                    onClick = {
                        currentSourceIndex = index
                        currentUrl = source.streamUrl
                        showSourcePickerModal = false
                    }
                )
            }
        } else {
            emptyList()
        }
    }

    if (showSourcePickerModal && pickerItems.isNotEmpty()) {
        SourceSwitcherDialog(
            items = pickerItems,
            onDismiss = { showSourcePickerModal = false }
        )
    }
}

@Composable
fun TrackSelectionDialog(
    exoPlayer: ExoPlayer,
    onDismiss: () -> Unit
) {
    val premiumBg = Color(0xFF09090B)
    val premiumSurface = Color(0xFF18181B)
    val premiumAccent = Color(0xFFFAFAFA)
    val premiumTextSec = Color(0xFFA1A1AA)

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var selectionVersion by remember { mutableIntStateOf(0) }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                selectionVersion++
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    val tracks = exoPlayer.currentTracks
    val audioTracks = remember(tracks, selectionVersion) {
        tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    }
    val subtitleTracks = remember(tracks, selectionVersion) {
        tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .width(if (isLandscape) 580.dp else 340.dp)
                .wrapContentHeight(),
            shape = RoundedCornerShape(24.dp),
            color = premiumSurface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Audio & Subtitles",
                        color = premiumAccent,
                        fontWeight = FontWeight.Black,
                        fontSize = 20.sp
                    )
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = premiumTextSec, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = if (isLandscape) 220.dp else 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text("Audio Tracks", color = Color(0xFF3B82F6), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }

                    if (audioTracks.isEmpty()) {
                        item {
                            Text("No alternative audio tracks available", color = premiumTextSec, fontSize = 13.sp)
                        }
                    } else {
                        audioTracks.forEach { group ->
                            for (i in 0 until group.length) {
                                val format = group.getTrackFormat(i)
                                val isSelected = group.isTrackSelected(i)
                                val label = format.language?.uppercase() ?: "Audio Track ${i + 1}"
                                item {
                                    Card(
                                        onClick = {
                                            val builder = exoPlayer.trackSelectionParameters.buildUpon()
                                            builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                                            exoPlayer.trackSelectionParameters = builder.build()
                                            selectionVersion++
                                        },
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isSelected) Color(0xFF27272A) else premiumBg
                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, premiumAccent) else null
                                    ) {
                                        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.AudioFile, null, tint = premiumAccent)
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Text(label, color = premiumAccent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Subtitles", color = Color(0xFF3B82F6), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }

                    item {
                        val isSubDisabled = exoPlayer.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
                        Card(
                            onClick = {
                                val builder = exoPlayer.trackSelectionParameters.buildUpon()
                                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                exoPlayer.trackSelectionParameters = builder.build()
                                selectionVersion++
                            },
                            colors = CardDefaults.cardColors(containerColor = if (isSubDisabled) Color(0xFF27272A) else premiumBg),
                            shape = RoundedCornerShape(12.dp),
                            border = if (isSubDisabled) androidx.compose.foundation.BorderStroke(1.dp, premiumAccent) else null
                        ) {
                            Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ClosedCaptionDisabled, null, tint = premiumTextSec)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Off", color = premiumAccent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }

                    subtitleTracks.forEach { group ->
                        for (i in 0 until group.length) {
                            val format = group.getTrackFormat(i)
                            val isSelected = group.isTrackSelected(i) && !exoPlayer.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
                            val label = format.language?.uppercase() ?: format.label ?: "Subtitle ${i + 1}"
                            item {
                                Card(
                                    onClick = {
                                        val builder = exoPlayer.trackSelectionParameters.buildUpon()
                                        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                        builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                                        exoPlayer.trackSelectionParameters = builder.build()
                                        selectionVersion++
                                    },
                                    colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF27272A) else premiumBg),
                                    shape = RoundedCornerShape(12.dp),
                                    border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, premiumAccent) else null
                                ) {
                                    Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.ClosedCaption, null, tint = premiumAccent)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(label, color = premiumAccent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
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

@Composable
fun SourceSwitcherDialog(
    items: List<PickerSourceItem>,
    onDismiss: () -> Unit
) {
    val premiumBg = Color(0xFF09090B)
    val premiumSurface = Color(0xFF18181B)
    val premiumAccent = Color(0xFFFAFAFA)
    val premiumTextSec = Color(0xFFA1A1AA)

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .width(if (isLandscape) 580.dp else 340.dp)
                .wrapContentHeight(),
            shape = RoundedCornerShape(24.dp),
            color = premiumSurface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Select Source",
                        color = premiumAccent,
                        fontWeight = FontWeight.Black,
                        fontSize = 20.sp
                    )
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = premiumTextSec, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = if (isLandscape) 220.dp else 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items) { item ->
                        Card(
                            onClick = item.onClick,
                            colors = CardDefaults.cardColors(
                                containerColor = if (item.isSelected) Color(0xFF27272A) else premiumBg
                            ),
                            shape = RoundedCornerShape(12.dp),
                            border = if (item.isSelected) androidx.compose.foundation.BorderStroke(1.dp, premiumAccent) else null
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        color = premiumAccent,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = item.subtitle,
                                        color = premiumTextSec,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            when (item.typeTag) {
                                                "EXTREME" -> Color(0xFFE50914)
                                                else -> Color(0xFF3B82F6)
                                            }
                                        )
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = item.typeTag,
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Black
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

@Composable
fun QualitySwitcherDialog(
    exoPlayer: ExoPlayer?,
    trackSelector: DefaultTrackSelector?,
    selectedQualityKey: String,
    onQualitySelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val premiumBg = Color(0xFF09090B)
    val premiumSurface = Color(0xFF18181B)
    val premiumAccent = Color(0xFFFAFAFA)
    val premiumTextSec = Color(0xFFA1A1AA)

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .width(if (isLandscape) 580.dp else 340.dp)
                .wrapContentHeight(),
            shape = RoundedCornerShape(24.dp),
            color = premiumSurface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Select Quality",
                        color = premiumAccent,
                        fontWeight = FontWeight.Black,
                        fontSize = 20.sp
                    )
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = premiumTextSec, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = if (isLandscape) 220.dp else 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isAutoSelected = selectedQualityKey == "auto"
                    item {
                        Card(
                            onClick = {
                                trackSelector?.let { ts ->
                                    ts.setParameters(ts.buildUponParameters().clearVideoSizeConstraints().clearOverrides().build())
                                }
                                onQualitySelected("auto")
                                onDismiss()
                            },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isAutoSelected) Color(0xFF27272A) else premiumBg
                            ),
                            shape = RoundedCornerShape(12.dp),
                            border = if (isAutoSelected) androidx.compose.foundation.BorderStroke(1.dp, premiumAccent) else null
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Auto (Adaptive)", color = premiumAccent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                QualityBoxBadge(tag = "AUTO")
                            }
                        }
                    }

                    val videoGroups = exoPlayer?.currentTracks?.groups?.filter { it.type == C.TRACK_TYPE_VIDEO } ?: emptyList()
                    videoGroups.forEach { group ->
                        for (i in 0 until group.length) {
                            val format = group.getTrackFormat(i)
                            if (format.height > 0) {
                                val tag = getQualityTag(format.height)
                                val qualityKey = "${format.height}p"
                                val isSelected = selectedQualityKey == qualityKey

                                item {
                                    Card(
                                        onClick = {
                                            trackSelector?.let { ts ->
                                                ts.setParameters(ts.buildUponParameters().clearOverrides().addOverride(TrackSelectionOverride(group.mediaTrackGroup, i)).build())
                                            }
                                            onQualitySelected(qualityKey)
                                            onDismiss()
                                        },
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isSelected) Color(0xFF27272A) else premiumBg
                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, premiumAccent) else null
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(qualityKey, color = premiumAccent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                            QualityBoxBadge(tag = tag)
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
}

@Composable
fun ResizeModeDialog(
    currentResizeMode: Int,
    onDismiss: () -> Unit,
    onResizeModeSelected: (Int) -> Unit
) {
    val premiumBg = Color(0xFF09090B)
    val premiumSurface = Color(0xFF18181B)
    val premiumAccent = Color(0xFFFAFAFA)
    val premiumTextSec = Color(0xFFA1A1AA)

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val resizeModes = listOf(
        Triple("Fit", AspectRatioFrameLayout.RESIZE_MODE_FIT, Icons.Default.FitScreen),
        Triple("Fill (Stretch)", AspectRatioFrameLayout.RESIZE_MODE_FILL, Icons.Default.Fullscreen),
        Triple("Zoom (Full Screen)", AspectRatioFrameLayout.RESIZE_MODE_ZOOM, Icons.Default.ZoomIn),
        Triple("Fixed Width", AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH, Icons.Default.WidthNormal),
        Triple("Fixed Height", AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT, Icons.Default.Height)
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .width(if (isLandscape) 580.dp else 340.dp)
                .wrapContentHeight(),
            shape = RoundedCornerShape(24.dp),
            color = premiumSurface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Screen Aspect Ratio",
                        color = premiumAccent,
                        fontWeight = FontWeight.Black,
                        fontSize = 20.sp
                    )
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = premiumTextSec, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = if (isLandscape) 220.dp else 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(resizeModes) { (label, mode, icon) ->
                        val isSelected = currentResizeMode == mode
                        Card(
                            onClick = { onResizeModeSelected(mode) },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) Color(0xFF27272A) else premiumBg
                            ),
                            shape = RoundedCornerShape(12.dp),
                            border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, premiumAccent) else null
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(icon, null, tint = if (isSelected) premiumAccent else premiumTextSec)
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    text = label,
                                    color = premiumAccent,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}