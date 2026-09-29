@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.vyan.iptv.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
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
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.vyan.iptv.data.managers.SettingsManager
import com.vyan.iptv.stream.*
import com.vyan.iptv.utils.ExoServerDiagnostic
import kotlinx.coroutines.delay

fun getQualityTag(height: Int): String {
    return when {
        height >= 4320 -> "8K UHD"
        height >= 2160 -> "4K UHD"
        height >= 1440 -> "QHD"
        height >= 1080 -> "FHD"
        height >= 720 -> "HD"
        height >= 540 -> "qHD"
        height >= 512 -> "SD+"
        height >= 480 -> "SD"
        height >= 360 -> "nHD"
        height >= 288 -> "CIF"
        height >= 216 -> "216p"
        height >= 144 -> "QCIF"
        height > 0 -> "Low"
        else -> "AUTO"
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
        Text(text = tag, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
    }
}

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun PlayerScreen(
    initialProfile: StreamProfile,
    title: String,
    isLiveStream: Boolean = true,
    settingsManager: SettingsManager? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val isPreview = LocalInspectionMode.current

    val premiumBg = Color.Black
    val premiumSurface = Color(0xFF121212)
    val premiumAccent = Color.White
    val premiumTextSec = Color(0xFFAAAAAA)
    val premiumRed = Color(0xFFE50914)

    var profile by remember { mutableStateOf(initialProfile) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var areControlsVisible by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(true) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isLiveWindow by remember { mutableStateOf(isLiveStream) }
    var currentVideoHeight by remember { mutableIntStateOf(0) }
    var selectedQualityKey by remember { mutableStateOf("auto") }
    var currentResizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    var showQualityPickerModal by remember { mutableStateOf(false) }
    var showResizeDialog by remember { mutableStateOf(false) }
    var showTrackSelectionDialog by remember { mutableStateOf(false) }
    var showHamburgerMenu by remember { mutableStateOf(false) }

    val trackSelector = remember { if (isPreview) null else DefaultTrackSelector(context) }
    val activeDecoderMode = remember { settingsManager?.decoderMode ?: "auto" }

    val exoPlayer = remember {
        if (isPreview) null else {
            val extensionMode = when (activeDecoderMode) {
                "software" -> androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                "prefer_software" -> androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                "hardware" -> androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                else -> androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            }
            val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context)
                .setExtensionRendererMode(extensionMode)
                .setEnableDecoderFallback(true)

            ExoPlayer.Builder(context, renderersFactory)
                .setTrackSelector(trackSelector!!)
                .build()
        }
    }

    BackHandler {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onBack()
    }

    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            exoPlayer?.release()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // MANDATORY ROUTING PIPELINE: Every stream is universally routed here
    LaunchedEffect(profile) {
        if (isPreview || exoPlayer == null) return@LaunchedEffect
        isLoading = true
        errorMessage = null

        try {
            val routedProfile = PlaybackMethodRouter.decideAndRoute(profile)

            val workingProfile = if (routedProfile.method == PlaybackMethod.WEB_RESOLVER) {
                WebEmbedPlaybackBuilder.resolve(context, routedProfile)
            } else {
                routedProfile
            }

            val mediaSource = when (workingProfile.method) {
                PlaybackMethod.LOCAL_PROXY -> LocalProxyPlaybackBuilder.buildMediaSource(context, workingProfile)
                else -> DirectHttpPlaybackBuilder.buildMediaSource(context, workingProfile)
            }

            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        } catch (e: Exception) {
            isLoading = false
            errorMessage = e.localizedMessage ?: "Failed to load stream"
        }
    }

    DisposableEffect(exoPlayer) {
        if (exoPlayer == null) return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                isLoading = false
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_TIMELINE_CHANGED) || events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                    val duration = player.duration
                    val newIsLive = player.isCurrentWindowDynamic || duration == C.TIME_UNSET || duration <= 0L
                    if (isLiveWindow != newIsLive) isLiveWindow = newIsLive
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> isLoading = true
                    Player.STATE_READY -> {
                        isLoading = false
                        val newDuration = exoPlayer.duration
                        durationMs = if (newDuration == C.TIME_UNSET) 0L else newDuration
                    }
                    Player.STATE_ENDED -> isLoading = false
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height > 0) {
                    currentVideoHeight = videoSize.height
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                isLoading = false
                val cause = error.cause
                val isDecoderCrash = cause?.toString()?.contains("MediaCodec") == true || error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED

                errorMessage = when {
                    isDecoderCrash -> "Codec Error: Stream blocked or format unsupported."
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "Stream blocked. Server returned HTML instead of video."
                    else -> ExoServerDiagnostic.extractExactError(error)
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    LaunchedEffect(exoPlayer) {
        if (exoPlayer == null) return@LaunchedEffect
        while (true) {
            if (exoPlayer.isPlaying) {
                val newPos = exoPlayer.currentPosition
                if (kotlin.math.abs(currentPositionMs - newPos) >= 500) {
                    currentPositionMs = newPos
                }
            }
            delay(500)
        }
    }

    LaunchedEffect(areControlsVisible, showQualityPickerModal, showResizeDialog, showTrackSelectionDialog, showHamburgerMenu) {
        if (areControlsVisible && !showQualityPickerModal && !showResizeDialog && !showTrackSelectionDialog && !showHamburgerMenu) {
            delay(4500)
            areControlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(premiumBg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { areControlsVisible = !areControlsVisible }
    ) {
        if (isPreview || exoPlayer == null) {
            Box(modifier = Modifier.fillMaxSize().background(premiumBg), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayCircle, null, tint = premiumAccent.copy(alpha = 0.2f), modifier = Modifier.size(72.dp))
            }
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
                update = { view -> view.resizeMode = currentResizeMode },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (isLoading && errorMessage == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = premiumRed, strokeWidth = 3.dp)
            }
        }

        if (errorMessage != null) {
            Box(modifier = Modifier.fillMaxSize().background(premiumBg.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Icon(Icons.Default.Error, null, tint = premiumRed, modifier = Modifier.size(64.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Playback Failed", color = premiumAccent, fontWeight = FontWeight.Black, fontSize = 20.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(errorMessage!!, color = premiumTextSec, fontSize = 14.sp, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { onBack() },
                        colors = ButtonDefaults.buttonColors(containerColor = premiumAccent, contentColor = premiumBg),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Go Back", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        AnimatedVisibility(visible = areControlsVisible && errorMessage == null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxWidth().height(160.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(premiumBg.copy(alpha = 0.85f), Color.Transparent))))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .displayCutoutPadding()
                        .padding(top = 24.dp, start = 24.dp, end = 24.dp)
                        .align(Alignment.TopCenter),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED; onBack() }, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(premiumSurface.copy(alpha = 0.8f)).size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = premiumAccent)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(title, color = premiumAccent, fontWeight = FontWeight.Black, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                                    modifier = Modifier.size(56.dp).clip(CircleShape).background(premiumBg.copy(alpha = 0.5f))
                                ) {
                                    Icon(Icons.Default.Replay10, contentDescription = "Rewind 10s", tint = premiumAccent, modifier = Modifier.size(32.dp))
                                }
                            }
                        } else { Spacer(modifier = Modifier.weight(1f)) }

                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            IconButton(
                                onClick = { if (exoPlayer?.isPlaying == true) exoPlayer?.pause() else exoPlayer?.play() },
                                modifier = Modifier.size(72.dp).clip(CircleShape).background(premiumAccent)
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Play/Pause",
                                    tint = premiumBg,
                                    modifier = Modifier.size(42.dp)
                                )
                            }
                        }

                        if (!isLiveWindow) {
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                IconButton(
                                    onClick = { exoPlayer?.let { it.seekTo((it.currentPosition + 10000).coerceAtMost(durationMs)) } },
                                    modifier = Modifier.size(56.dp).clip(CircleShape).background(premiumBg.copy(alpha = 0.5f))
                                ) {
                                    Icon(Icons.Default.Forward10, contentDescription = "Forward 10s", tint = premiumAccent, modifier = Modifier.size(32.dp))
                                }
                            }
                        } else { Spacer(modifier = Modifier.weight(1f)) }
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().height(220.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, premiumBg.copy(alpha = 0.9f)))))

                Column(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 32.dp, vertical = 24.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        if (isLiveWindow) {
                            Spacer(modifier = Modifier.weight(1f))
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(premiumRed.copy(alpha = 0.2f)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(premiumRed))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("LIVE", color = premiumRed, fontSize = 13.sp, fontWeight = FontWeight.Black)
                            }
                        } else {
                            Text("${currentPositionMs / 60000}:${String.format("%02d", (currentPositionMs / 1000) % 60)}", color = premiumAccent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(if (durationMs > 0) "${durationMs / 60000}:${String.format("%02d", (durationMs / 1000) % 60)}" else "", color = premiumTextSec, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (isLiveWindow) {
                        Box(modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(premiumRed))
                    } else {
                        Slider(
                            value = currentPositionMs.toFloat(),
                            onValueChange = { exoPlayer?.seekTo(it.toLong()) },
                            valueRange = 0f..(if(durationMs > 0) durationMs.toFloat() else 100f),
                            colors = SliderDefaults.colors(thumbColor = premiumAccent, activeTrackColor = premiumAccent, inactiveTrackColor = premiumSurface)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Row(modifier = Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box {
                                IconButton(onClick = { showHamburgerMenu = true }, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(premiumSurface.copy(alpha = 0.8f)).size(48.dp)) {
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
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(premiumSurface.copy(alpha = 0.8f))
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
                                    onClick = { activity?.enterPictureInPictureMode(PictureInPictureParams.Builder().build()) },
                                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(premiumSurface.copy(alpha = 0.8f)).size(48.dp)
                                ) {
                                    Icon(Icons.Default.PictureInPicture, "PiP", tint = premiumAccent)
                                }
                            }
                        }

                        Row(modifier = Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            IconButton(onClick = { activity?.requestedOrientation = if (activity?.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(premiumSurface.copy(alpha = 0.8f)).size(48.dp)) {
                                Icon(Icons.Default.ScreenRotation, null, tint = premiumAccent)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showQualityPickerModal) QualitySwitcherDialog(exoPlayer, trackSelector, selectedQualityKey, { selectedQualityKey = it }, { showQualityPickerModal = false })
    if (showResizeDialog) ResizeModeDialog(currentResizeMode, { showResizeDialog = false }, { currentResizeMode = it; showResizeDialog = false })
    if (showTrackSelectionDialog && exoPlayer != null) TrackSelectionDialog(exoPlayer, { showTrackSelectionDialog = false })
}

@Composable
fun TrackSelectionDialog(exoPlayer: ExoPlayer, onDismiss: () -> Unit) {
    val premiumBg = Color(0xFF09090B); val premiumSurface = Color(0xFF121212); val premiumAccent = Color(0xFFFAFAFA); val premiumTextSec = Color(0xFFAAAAAA)
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var selectionVersion by remember { mutableIntStateOf(0) }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener { override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { selectionVersion++ } }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    val tracks = exoPlayer.currentTracks
    val audioTracks = remember(tracks, selectionVersion) { tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO } }
    val subtitleTracks = remember(tracks, selectionVersion) { tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.width(if (isLandscape) 580.dp else 340.dp).wrapContentHeight(), shape = RoundedCornerShape(16.dp), color = premiumSurface, tonalElevation = 0.dp) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Audio & Subtitles", color = premiumAccent, fontWeight = FontWeight.Black, fontSize = 20.sp)
                    TextButton(onClick = onDismiss) { Text("Close", color = premiumTextSec, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                }
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = if (isLandscape) 220.dp else 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text("Audio Tracks", color = Color(0xFF3B82F6), fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                    if (audioTracks.isEmpty()) item { Text("No alternative audio tracks available", color = premiumTextSec, fontSize = 13.sp) }
                    else {
                        audioTracks.forEach { group ->
                            for (i in 0 until group.length) {
                                val format = group.getTrackFormat(i); val isSelected = group.isTrackSelected(i)
                                item {
                                    Card(
                                        onClick = { val builder = exoPlayer.trackSelectionParameters.buildUpon(); builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i)); exoPlayer.trackSelectionParameters = builder.build(); selectionVersion++ },
                                        colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF27272A) else premiumBg), shape = RoundedCornerShape(10.dp), border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, premiumAccent) else null
                                    ) { Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.AudioFile, null, tint = premiumAccent); Spacer(modifier = Modifier.width(12.dp)); Text(format.language?.uppercase() ?: "Audio Track ${i + 1}", color = premiumAccent, fontWeight = FontWeight.Bold, fontSize = 14.sp) } }
                                }
                            }
                        }
                    }
                    item { Spacer(modifier = Modifier.height(8.dp)); Text("Subtitles", color = Color(0xFF3B82F6), fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                    item {
                        val isSubDisabled = exoPlayer.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
                        Card(
                            onClick = { val builder = exoPlayer.trackSelectionParameters.buildUpon(); builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true); exoPlayer.trackSelectionParameters = builder.build(); selectionVersion++ },
                            colors = CardDefaults.cardColors(containerColor = if (isSubDisabled) Color(0xFF27272A) else premiumBg), shape = RoundedCornerShape(10.dp), border = if (isSubDisabled) androidx.compose.foundation.BorderStroke(1.dp, premiumAccent) else null
                        ) { Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.ClosedCaptionDisabled, null, tint = premiumTextSec); Spacer(modifier = Modifier.width(12.dp)); Text("Off", color = premiumAccent, fontWeight = FontWeight.Bold, fontSize = 14.sp) } }
                    }
                    subtitleTracks.forEach { group ->
                        for (i in 0 until group.length) {
                            val format = group.getTrackFormat(i); val isSelected = group.isTrackSelected(i) && !exoPlayer.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
                            item {
                                Card(
                                    onClick = { val builder = exoPlayer.trackSelectionParameters.buildUpon(); builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false); builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i)); exoPlayer.trackSelectionParameters = builder.build(); selectionVersion++ },
                                    colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF27272A) else premiumBg), shape = RoundedCornerShape(10.dp), border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, premiumAccent) else null
                                ) { Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.ClosedCaption, null, tint = premiumAccent); Spacer(modifier = Modifier.width(12.dp)); Text(format.language?.uppercase() ?: format.label ?: "Subtitle ${i + 1}", color = premiumAccent, fontWeight = FontWeight.Bold, fontSize = 14.sp) } }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QualitySwitcherDialog(exoPlayer: ExoPlayer?, trackSelector: DefaultTrackSelector?, selectedQualityKey: String, onQualitySelected: (String) -> Unit, onDismiss: () -> Unit) {
    val premiumBg = Color(0xFF09090B); val premiumSurface = Color(0xFF121212); val premiumAccent = Color(0xFFFAFAFA); val premiumTextSec = Color(0xFFAAAAAA)
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.width(if (isLandscape) 580.dp else 340.dp).wrapContentHeight(), shape = RoundedCornerShape(16.dp), color = premiumSurface, tonalElevation = 0.dp) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Select Quality", color = premiumAccent, fontWeight = FontWeight.Black, fontSize = 20.sp); TextButton(onClick = onDismiss) { Text("Close", color = premiumTextSec, fontWeight = FontWeight.Bold, fontSize = 14.sp) } }
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = if (isLandscape) 220.dp else 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val isAutoSelected = selectedQualityKey == "auto"
                    item {
                        Card(
                            onClick = { trackSelector?.let { ts -> ts.setParameters(ts.buildUponParameters().clearVideoSizeConstraints().clearOverrides().build()) }; onQualitySelected("auto"); onDismiss() },
                            colors = CardDefaults.cardColors(containerColor = if (isAutoSelected) Color(0xFF27272A) else premiumBg), shape = RoundedCornerShape(10.dp), border = if (isAutoSelected) androidx.compose.foundation.BorderStroke(1.dp, premiumAccent) else null
                        ) { Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("Auto (Adaptive)", color = premiumAccent, fontWeight = FontWeight.Bold, fontSize = 15.sp); QualityBoxBadge("AUTO") } }
                    }
                    val videoGroups = exoPlayer?.currentTracks?.groups?.filter { it.type == C.TRACK_TYPE_VIDEO } ?: emptyList()
                    videoGroups.forEach { group ->
                        for (i in 0 until group.length) {
                            val format = group.getTrackFormat(i)
                            if (format.height > 0) {
                                val qualityKey = "${format.height}p"; val isSelected = selectedQualityKey == qualityKey
                                item {
                                    Card(
                                        onClick = { trackSelector?.let { ts -> ts.setParameters(ts.buildUponParameters().clearOverrides().addOverride(TrackSelectionOverride(group.mediaTrackGroup, i)).build()) }; onQualitySelected(qualityKey); onDismiss() },
                                        colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF27272A) else premiumBg), shape = RoundedCornerShape(10.dp), border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, premiumAccent) else null
                                    ) { Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text(qualityKey, color = premiumAccent, fontWeight = FontWeight.Bold, fontSize = 15.sp); QualityBoxBadge(getQualityTag(format.height)) } }
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
fun ResizeModeDialog(currentResizeMode: Int, onDismiss: () -> Unit, onResizeModeSelected: (Int) -> Unit) {
    val premiumBg = Color(0xFF09090B); val premiumSurface = Color(0xFF121212); val premiumAccent = Color(0xFFFAFAFA); val premiumTextSec = Color(0xFFAAAAAA)
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val resizeModes = listOf(Triple("Fit", AspectRatioFrameLayout.RESIZE_MODE_FIT, Icons.Default.FitScreen), Triple("Fill (Stretch)", AspectRatioFrameLayout.RESIZE_MODE_FILL, Icons.Default.Fullscreen), Triple("Zoom (Full Screen)", AspectRatioFrameLayout.RESIZE_MODE_ZOOM, Icons.Default.ZoomIn), Triple("Fixed Width", AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH, Icons.Default.WidthNormal), Triple("Fixed Height", AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT, Icons.Default.Height))

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.width(if (isLandscape) 580.dp else 340.dp).wrapContentHeight(), shape = RoundedCornerShape(16.dp), color = premiumSurface, tonalElevation = 0.dp) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Screen Aspect Ratio", color = premiumAccent, fontWeight = FontWeight.Black, fontSize = 20.sp); TextButton(onClick = onDismiss) { Text("Close", color = premiumTextSec, fontWeight = FontWeight.Bold, fontSize = 14.sp) } }
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = if (isLandscape) 220.dp else 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(resizeModes) { (label, mode, icon) ->
                        val isSelected = currentResizeMode == mode
                        Card(
                            onClick = { onResizeModeSelected(mode) }, colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF27272A) else premiumBg), shape = RoundedCornerShape(10.dp), border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, premiumAccent) else null
                        ) { Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = if (isSelected) premiumAccent else premiumTextSec); Spacer(modifier = Modifier.width(16.dp)); Text(label, color = premiumAccent, fontWeight = FontWeight.Bold, fontSize = 15.sp) } }
                    }
                }
            }
        }
    }
}