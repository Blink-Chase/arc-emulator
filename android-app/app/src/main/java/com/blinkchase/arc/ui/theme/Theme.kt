package com.blinkchase.arc.ui.theme

import android.app.Activity
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat

private val DarkColorScheme = darkColorScheme(
    primary = ArcCyan,
    onPrimary = Color.Black,
    primaryContainer = ArcCyan.copy(alpha = 0.15f),
    onPrimaryContainer = ArcCyan,
    secondary = ArcBlue,
    onSecondary = Color.White,
    secondaryContainer = ArcCyan.copy(alpha = 0.2f),
    onSecondaryContainer = Color.White,
    background = ArcDarkBg,
    onBackground = Color.White,
    surface = ArcSurface,
    onSurface = Color.White,
    surfaceVariant = ArcSurface.copy(alpha = 0.7f),
    onSurfaceVariant = Color.LightGray
)

private val LightColorScheme = lightColorScheme(
    primary = ArcTeal,
    onPrimary = Color.White,
    secondary = ArcDeepBlue,
    onSecondary = Color.White,
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF1C1F22),
    surface = Color.White,
    onSurface = Color(0xFF1C1F22)
)

@Composable
fun ArcEmuTheme(
    darkTheme: Boolean? = null,
    dynamicColor: Boolean = false, // Set to false to prioritize brand identity
    content: @Composable () -> Unit
) {
    val useDarkTheme = darkTheme ?: isSystemInDarkTheme()
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (useDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        useDarkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    SideEffect {
        var context = view.context
        while (context !is Activity && context is ContextWrapper) {
            context = context.baseContext
        }
        val activity = context as? Activity ?: return@SideEffect
        val window = activity.window
        window.navigationBarColor = colorScheme.background.toArgb()
        window.navigationBarDividerColor = colorScheme.background.toArgb()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, view).isAppearanceLightNavigationBars = !useDarkTheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
