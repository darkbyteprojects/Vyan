package com.vyan.xtreamplayer.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val PremiumDarkColorScheme = darkColorScheme(
    background = PremiumBackground,
    surface = PremiumSurface,
    surfaceVariant = PremiumSurfaceVariant,
    primary = PremiumBlue,
    onPrimary = PremiumAccent,
    secondary = PremiumTextSecondary,
    onSecondary = PremiumAccent,
    error = PremiumRed,
    onBackground = PremiumAccent,
    onSurface = PremiumAccent,
    onSurfaceVariant = PremiumAccent
)

@Composable
fun XtreamPlayerTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = PremiumDarkColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}