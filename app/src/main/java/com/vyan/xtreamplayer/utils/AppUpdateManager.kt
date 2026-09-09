package com.vyan.xtreamplayer.utils

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class GithubReleaseInfo(
    val version: String,
    val releaseNotes: String,
    val downloadUrl: String
)

object AppUpdateManager {
    // FIXED: Added 'suspend' keyword here
    suspend fun checkGithubForUpdate(currentVersion: String): GithubReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://vyan.dbprojects.workers.dev/release/latest")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("X-Client-ID", "CloudPlay-Premium")

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                val latestVersion = json.getString("version").removePrefix("v").trim()

                // Strip prefixes and suffixes like "-beta" for comparison
                val currentClean = currentVersion.removePrefix("v").substringBefore("-").trim()

                // Only prompt an update if the remote version is strictly newer
                if (isNewerVersion(latestVersion, currentClean)) {
                    val body = json.optString("release_notes", "Bug fixes and performance improvements.")
                    val downloadUrl = json.getString("download_url")
                    return@withContext GithubReleaseInfo(latestVersion, body, downloadUrl)
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    // Compares semantic versions (e.g., "0.00.09" > "0.00.08")
    private fun isNewerVersion(remote: String, local: String): Boolean {
        if (remote == local) return false
        val remoteParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val localParts = local.split(".").mapNotNull { it.toIntOrNull() }
        val maxLength = maxOf(remoteParts.size, localParts.size)

        for (i in 0 until maxLength) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }
}

@Composable
fun AppUpdateDialog(
    updateInfo: GithubReleaseInfo?,
    onDismiss: () -> Unit
) {
    if (updateInfo == null) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var updateDownloadProgress by remember { mutableFloatStateOf(0f) }
    var isUpdateReadyToInstall by remember { mutableStateOf(false) }
    var downloadedApkUri by remember { mutableStateOf<Uri?>(null) }

    val premiumBg = Color(0xFF09090B)
    val premiumSurface = Color(0xFF18181B)
    val premiumAccent = Color(0xFFFAFAFA)
    val premiumTextSec = Color(0xFFA1A1AA)

    fun installApk() {
        downloadedApkUri?.let { uri ->
            try {
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                context.startActivity(installIntent)
                onDismiss()
            } catch (_: Exception) {
                Toast.makeText(context, "Could not launch installer.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun startUpdateDownload() {
        isDownloadingUpdate = true
        updateDownloadProgress = 0f
        isUpdateReadyToInstall = false

        scope.launch(Dispatchers.IO) {
            try {
                val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val uri = Uri.parse(updateInfo.downloadUrl)

                val request = DownloadManager.Request(uri).apply {
                    setTitle("Downloading App Update")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "App_Update.apk")
                    addRequestHeader("X-Client-ID", "CloudPlay-Premium")
                }

                val downloadId = downloadManager.enqueue(request)
                var downloading = true

                while (downloading) {
                    val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
                    if (cursor != null && cursor.moveToFirst()) {
                        val bytesDownloadedCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val bytesTotalCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)

                        val bytesDownloaded = if (bytesDownloadedCol != -1) cursor.getInt(bytesDownloadedCol) else 0
                        val bytesTotal = if (bytesTotalCol != -1) cursor.getInt(bytesTotalCol) else 0
                        val status = if (statusCol != -1) cursor.getInt(statusCol) else 0

                        if (bytesTotal > 0) {
                            withContext(Dispatchers.Main) {
                                updateDownloadProgress = bytesDownloaded.toFloat() / bytesTotal.toFloat()
                            }
                        }

                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            downloading = false
                            withContext(Dispatchers.Main) {
                                updateDownloadProgress = 1.0f
                                downloadedApkUri = downloadManager.getUriForDownloadedFile(downloadId)
                                delay(300)
                                isUpdateReadyToInstall = true
                                isDownloadingUpdate = false
                            }
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            downloading = false
                            withContext(Dispatchers.Main) {
                                isDownloadingUpdate = false
                                Toast.makeText(context, "Download failed.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    cursor?.close()
                    delay(100)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isDownloadingUpdate = false
                    Toast.makeText(context, "Error: ${e.localizedMessage ?: "Unknown error"}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!isDownloadingUpdate) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !isDownloadingUpdate, dismissOnClickOutside = !isDownloadingUpdate)
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = premiumSurface),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = "Update", tint = premiumAccent, modifier = Modifier.size(28.dp))
                    Text("Update Available", color = premiumAccent, fontWeight = FontWeight.Black, fontSize = 22.sp)
                }

                Text("Version ${updateInfo.version} is now available.", color = premiumAccent, fontWeight = FontWeight.Bold)

                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF27272A)).padding(16.dp)) {
                    Text(text = updateInfo.releaseNotes, color = premiumTextSec, fontSize = 14.sp, lineHeight = 20.sp)
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (isDownloadingUpdate) {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val animatedProgress by animateFloatAsState(targetValue = updateDownloadProgress.coerceIn(0f, 1f), animationSpec = tween(200, easing = FastOutSlowInEasing), label = "bar_progress")
                            Box(modifier = Modifier.width(220.dp).height(3.dp).clip(RoundedCornerShape(1.5.dp)).background(Color(0xFF27272A)), contentAlignment = Alignment.CenterStart) {
                                if (animatedProgress > 0.01f) {
                                    Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(animatedProgress).clip(RoundedCornerShape(1.5.dp)).background(Brush.horizontalGradient(listOf(Color(0xFFFAFAFA), Color(0xFFA1A1AA)))))
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(text = "DOWNLOADING ${(animatedProgress * 100).toInt()}%", color = Color(0xFFA1A1AA), fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.5.sp)
                        }
                    }
                } else if (isUpdateReadyToInstall) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f).height(50.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = premiumAccent),
                            border = BorderStroke(1.dp, premiumTextSec)
                        ) { Text("Cancel", fontWeight = FontWeight.Bold) }

                        Button(
                            onClick = { installApk() },
                            modifier = Modifier.weight(1f).height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = premiumAccent, contentColor = premiumBg)
                        ) { Text("Update", fontWeight = FontWeight.Black) }
                    }
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f).height(50.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = premiumAccent),
                            border = BorderStroke(1.dp, premiumTextSec)
                        ) { Text("Later", fontWeight = FontWeight.Bold) }

                        Button(
                            onClick = { startUpdateDownload() },
                            modifier = Modifier.weight(1f).height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = premiumAccent, contentColor = premiumBg)
                        ) { Text("Download", fontWeight = FontWeight.Black) }
                    }
                }
            }
        }
    }
}