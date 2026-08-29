package com.vyan.xtreamplayer.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun ExtremeUnlockLock(
    isAlreadyUnlocked: Boolean,
    onUnlockSuccess: () -> Unit
) {
    if (isAlreadyUnlocked) return

    val context = LocalContext.current
    var isHolding by remember { mutableStateOf(false) }
    var holdProgress by remember { mutableFloatStateOf(0f) }
    val animatedProgress by animateFloatAsState(
        targetValue = holdProgress,
        animationSpec = tween(durationMillis = 100),
        label = "hold_progress"
    )

    @SuppressLint("MissingPermission")
    fun vibrateDevice(durationMs: Long, amplitude: Int) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(durationMs, amplitude.coerceIn(1, 255))
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude.coerceIn(1, 255)))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(durationMs)
                }
            }
        } catch (_: Exception) {}
    }

    LaunchedEffect(isHolding) {
        if (isHolding) {
            val totalSteps = 150 // 15 seconds (150 * 100ms)
            var currentStep = 0
            while (isActive && isHolding && currentStep < totalSteps) {
                delay(100)
                currentStep++
                holdProgress = currentStep / totalSteps.toFloat()

                // Vibration increases in frequency & amplitude every second
                val intensity = (50 + (holdProgress * 205)).toInt()
                val pulseInterval = (15 - (holdProgress * 10)).toInt().coerceAtLeast(1)
                if (currentStep % pulseInterval == 0) {
                    vibrateDevice(40L, intensity)
                }
            }
            if (currentStep >= totalSteps) {
                vibrateDevice(500L, 255)
                onUnlockSuccess()
            }
        } else {
            holdProgress = 0f
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(140.dp) // Massively increased tap area
                .clip(CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isHolding = true
                            tryAwaitRelease()
                            isHolding = false
                        },
                        onTap = {
                            // Single tap triggers the tooltip instantly
                            Toast.makeText(context, "Hold for 15 seconds to unlock Extreme Mode", Toast.LENGTH_SHORT).show()
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            // Circular progress ring wrapping the lock button
            if (isHolding) {
                CircularProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 6.dp
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp)
                    .clip(CircleShape)
                    .background(if (isHolding) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (animatedProgress >= 1f) Icons.Default.LockOpen else Icons.Default.Lock,
                    contentDescription = "Hidden Lock",
                    tint = if (isHolding) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(52.dp)
                )
            }
        }
    }
}