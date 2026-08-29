@file:OptIn(ExperimentalMaterial3Api::class)

package com.vyan.xtreamplayer.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vyan.xtreamplayer.data.managers.AccountManager
import com.vyan.xtreamplayer.models.AccountType
import com.vyan.xtreamplayer.models.UserAccount
import com.vyan.xtreamplayer.network.XtreamApi
import kotlinx.coroutines.launch

fun cleanXtreamUrl(rawUrl: String): String {
    var url = rawUrl.trim().replace(Regex("\\s+"), ""); val qIdx = url.indexOf('?'); if (qIdx >= 0) url = url.substring(0, qIdx)
    while (url.endsWith("/")) url = url.substring(0, url.length - 1)
    url = url.replace(Regex("/(?:get\\.php|live\\.php|portal\\.php|c|index\\.php|playlist\\.php|player_api\\.php|xmltv\\.php|get|live|portal|index|playlist|player_api|xmltv)$", RegexOption.IGNORE_CASE), "")
    while (url.endsWith("/")) url = url.substring(0, url.length - 1); if (!url.startsWith("http")) url = "http://$url"; return url
}

@Composable
fun LoginScreen(accountManager: AccountManager, onLoginSuccess: () -> Unit, onBack: () -> Unit) {
    val premiumBg = Color(0xFF09090B); val premiumSurface = Color(0xFF18181B); val premiumAccent = Color(0xFFFAFAFA); val premiumTextSec = Color(0xFFA1A1AA); val premiumRed = Color(0xFFE50914)
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var selectedType by remember { mutableStateOf(AccountType.XTREAM) }; var alias by remember { mutableStateOf("") }; var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }; var isPasswordVisible by remember { mutableStateOf(false) }
    var m3uUrl by remember { mutableStateOf("") }; var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    val filePickerLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument()) { uri: Uri? -> selectedFileUri = uri; if (alias.isEmpty() && uri != null) alias = "Local Playlist" }
    var isLoading by remember { mutableStateOf(false) }; var errorMessage by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(premiumBg).statusBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp).verticalScroll(rememberScrollState())) {
            // FIXED: Top Padding reduced to 8.dp
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.clip(CircleShape).background(premiumSurface).size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = premiumAccent) }
                Spacer(modifier = Modifier.width(20.dp))
                Text(text = "Add Playlist", color = premiumAccent, fontSize = 32.sp, fontWeight = FontWeight.Black)
            }

            Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(premiumSurface).padding(8.dp)) {
                LoginTypeTab(title = "Xtream", isSelected = selectedType == AccountType.XTREAM, modifier = Modifier.weight(1f)) { selectedType = AccountType.XTREAM; errorMessage = null }
                LoginTypeTab(title = "M3U Link", isSelected = selectedType == AccountType.M3U_URL, modifier = Modifier.weight(1f)) { selectedType = AccountType.M3U_URL; errorMessage = null }
                LoginTypeTab(title = "Local File", isSelected = selectedType == AccountType.M3U_FILE, modifier = Modifier.weight(1f)) { selectedType = AccountType.M3U_FILE; errorMessage = null }
            }
            Spacer(modifier = Modifier.height(40.dp))

            val textFieldColors = TextFieldDefaults.colors(focusedContainerColor = premiumSurface, unfocusedContainerColor = premiumSurface, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedTextColor = premiumAccent, unfocusedTextColor = premiumAccent)

            TextField(value = alias, onValueChange = { alias = it }, placeholder = { Text("Playlist Name (Optional)", color = premiumTextSec, fontWeight = FontWeight.Medium) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = textFieldColors, singleLine = true)
            Spacer(modifier = Modifier.height(20.dp))

            when (selectedType) {
                AccountType.XTREAM -> {
                    TextField(value = serverUrl, onValueChange = { serverUrl = it }, placeholder = { Text("Server URL (http://...)", color = premiumTextSec, fontWeight = FontWeight.Medium) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = textFieldColors, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    Spacer(modifier = Modifier.height(20.dp))
                    TextField(value = username, onValueChange = { username = it }, placeholder = { Text("Username", color = premiumTextSec, fontWeight = FontWeight.Medium) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = textFieldColors, singleLine = true)
                    Spacer(modifier = Modifier.height(20.dp))
                    TextField(
                        value = password, onValueChange = { password = it }, placeholder = { Text("Password", color = premiumTextSec, fontWeight = FontWeight.Medium) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = textFieldColors, singleLine = true,
                        visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) { Icon(if (isPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff, null, tint = premiumTextSec) } }
                    )
                }
                AccountType.M3U_URL -> { TextField(value = m3uUrl, onValueChange = { m3uUrl = it }, placeholder = { Text("M3U / M3U8 Link", color = premiumTextSec, fontWeight = FontWeight.Medium) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = textFieldColors, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)) }
                AccountType.M3U_FILE -> {
                    Card(modifier = Modifier.fillMaxWidth().height(160.dp).clickable { filePickerLauncher.launch(arrayOf("*/*")) }, colors = CardDefaults.cardColors(containerColor = premiumSurface), shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(0.dp)) {
                        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(Icons.Default.UploadFile, contentDescription = null, tint = premiumAccent, modifier = Modifier.size(48.dp)); Spacer(modifier = Modifier.height(16.dp))
                            Text(text = if (selectedFileUri == null) "Tap to select .m3u file" else "File Selected", color = if (selectedFileUri == null) premiumTextSec else premiumAccent, fontWeight = FontWeight.Black, fontSize = 18.sp)
                        }
                    }
                }
            }

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(premiumRed.copy(alpha = 0.1f)).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ErrorOutline, null, tint = premiumRed, modifier = Modifier.size(28.dp)); Spacer(modifier = Modifier.width(16.dp))
                    Text(errorMessage!!, color = premiumRed, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(48.dp))
            Button(
                onClick = {
                    if (isLoading) return@Button
                    errorMessage = null
                    val finalAlias = alias.ifBlank { when (selectedType) { AccountType.XTREAM -> username; AccountType.M3U_URL -> "Web Playlist"; AccountType.M3U_FILE -> "Local File" } }
                    when (selectedType) {
                        AccountType.XTREAM -> {
                            if (serverUrl.isBlank() || username.isBlank() || password.isBlank()) { errorMessage = "Please fill in all Xtream fields"; return@Button }
                            isLoading = true
                            scope.launch {
                                try {
                                    val safeUrl = cleanXtreamUrl(serverUrl); val cleanUrl = XtreamApi.formatApiUrl(safeUrl)
                                    val response = XtreamApi.service.authenticate(cleanUrl, username, password)
                                    if (response.isSuccessful && response.body() != null && response.body()!!.userInfo?.isValid() == true) {
                                        val acc = UserAccount(
                                            url = cleanUrl,
                                            username = username,
                                            pass = password,
                                            alias = finalAlias,
                                            type = AccountType.XTREAM
                                        )
                                        accountManager.addAccount(acc); accountManager.setActiveAccount(acc.id); onLoginSuccess()
                                    } else { errorMessage = "Invalid credentials or dead server." }
                                } catch (e: Exception) { errorMessage = "Network error: Cannot reach server." } finally { isLoading = false }
                            }
                        }
                        AccountType.M3U_URL -> {
                            val cleanUrl = m3uUrl.trim()
                            if (cleanUrl.isBlank() || (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://"))) { errorMessage = "Please enter a valid HTTP/HTTPS link"; return@Button }
                            val acc = UserAccount(
                                url = cleanUrl,
                                username = "",
                                pass = "",
                                alias = finalAlias,
                                type = AccountType.M3U_URL
                            )
                            accountManager.addAccount(acc); accountManager.setActiveAccount(acc.id); onLoginSuccess()
                        }
                        AccountType.M3U_FILE -> {
                            if (selectedFileUri == null) { errorMessage = "Please select a file from your device"; return@Button }
                            try { context.contentResolver.takePersistableUriPermission(selectedFileUri!!, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { e.printStackTrace() }
                            val acc = UserAccount(
                                url = "",
                                localFilePath = selectedFileUri.toString(),
                                username = "",
                                pass = "",
                                alias = finalAlias,
                                type = AccountType.M3U_FILE
                            )
                            accountManager.addAccount(acc); accountManager.setActiveAccount(acc.id); onLoginSuccess()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(20.dp), colors = ButtonDefaults.buttonColors(containerColor = premiumAccent)
            ) {
                if (isLoading) CircularProgressIndicator(color = premiumBg, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                else Text("Add Playlist", fontSize = 18.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.SansSerif, color = premiumBg)
            }
        }
    }
}

@Composable
fun LoginTypeTab(title: String, isSelected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier.clip(RoundedCornerShape(14.dp)).background(if (isSelected) Color(0xFFFAFAFA) else Color.Transparent).clickable { onClick() }.padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        // FIXED: Bold + Standard Font for Tabs
        Text(text = title, color = if (isSelected) Color(0xFF09090B) else Color(0xFFA1A1AA), fontWeight = FontWeight.Black, fontFamily = FontFamily.SansSerif, fontSize = 15.sp)
    }
}