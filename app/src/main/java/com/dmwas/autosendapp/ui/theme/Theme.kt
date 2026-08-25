package com.dmwas.autosendapp.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary              = MpesaLightGreen,
    onPrimary            = Color(0xFF071310),
    primaryContainer     = Color(0xFF145A32),
    onPrimaryContainer   = Color(0xFFAAF0C4),
    secondary            = SafaricomAmber,
    onSecondary          = Color(0xFF1A0900),
    secondaryContainer   = Color(0xFF3D2000),
    onSecondaryContainer = Color(0xFFFFDDB3),
    tertiary             = MpesaAccentGreen,
    onTertiary           = Color(0xFF002110),
    background           = SurfaceDark,
    onBackground         = Color(0xFFE0F2E9),
    surface              = CardDark,
    onSurface            = Color(0xFFD5EDDD),
    surfaceVariant       = CardDarkElevated,
    onSurfaceVariant     = Color(0xFFA8CBAF),
    outline              = DividerDark,
    error                = ErrorRed,
    onError              = Color.White,
)

private val LightColorScheme = lightColorScheme(
    primary              = MpesaGreen,
    onPrimary            = Color.White,
    primaryContainer     = Color(0xFFCCF2DC),
    onPrimaryContainer   = MpesaDarkGreen,
    secondary            = SafaricomOrange,
    onSecondary          = Color.White,
    secondaryContainer   = Color(0xFFFFECE0),
    onSecondaryContainer = Color(0xFF3D1200),
    tertiary             = MpesaAccentGreen,
    onTertiary           = Color.White,
    background           = SurfaceLight,
    onBackground         = Color(0xFF0A1A10),
    surface              = CardLight,
    onSurface            = Color(0xFF112218),
    surfaceVariant       = Color(0xFFE0F0E5),
    onSurfaceVariant     = Color(0xFF2C5038),
    outline              = DividerLight,
    error                = ErrorRed,
    onError              = Color.White,
)

@Composable
fun AutoSendAppTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Transparent status bar so gradient bleeds to edge
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
