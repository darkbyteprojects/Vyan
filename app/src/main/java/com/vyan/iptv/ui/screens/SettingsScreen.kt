@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package com.vyan.iptv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.vyan.iptv.data.managers.AccountStorageManager
import com.vyan.iptv.data.managers.SettingsManager
import com.vyan.iptv.data.managers.DataCache
import com.vyan.iptv.models.AccountType
import com.vyan.iptv.models.UserAccount
import com.vyan.iptv.ui.components.CrashLogViewerDialog

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

    val appVersion = remember {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "Unknown"
        } catch (_: Exception) {
            "Unknown"
        }
    }

    var currentSettingsPage by rememberSaveable { mutableStateOf("main") }

    BackHandler(enabled = currentSettingsPage != "main") {
        currentSettingsPage = when (currentSettingsPage) {
            "edit_playlist" -> "playlists"
            "playlists" -> "main"
            else -> "main"
        }
    }

    var editingAccount by remember { mutableStateOf<UserAccount?>(null) }
    var accountsList by remember { mutableStateOf(accountStorageManager.getAccounts()) }
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