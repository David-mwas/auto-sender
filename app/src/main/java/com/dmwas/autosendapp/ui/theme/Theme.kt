package com.dmwas.autosendapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import android.app.Activity

private val DarkColorScheme = darkColorScheme(
    primary            = MpesaLightGreen,
    onPrimary          = Color(0xFF0D1F0D),
    primaryContainer   = MpesaDeepGreen,
    onPrimaryContainer = Color(0xFFB8F5B0),
    secondary          = SafaricomAmber,
    onSecondary        = Color(0xFF1A0A00),
    secondaryContainer = Color(0xFF4A2800),
    onSecondaryContainer = Color(0xFFFFDDB5),
    tertiary           = MpesaAccentGreen,
    onTertiary         = Color(0xFF0D1F0D),
    background         = SurfaceDark,
    onBackground       = Color(0xFFE4F5E0),
    surface            = CardDark,
    onSurface          = Color(0xFFDCF0D8),
    surfaceVariant     = CardDarkElevated,
    onSurfaceVariant   = Color(0xFFBAD5B5),
    outline            = DividerDark,
    error              = ErrorRed,
    onError            = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary            = MpesaGreen,
    onPrimary          = Color.White,
    primaryContainer   = Color(0xFFD7F5D0),
    onPrimaryContainer = MpesaDarkGreen,
    secondary          = SafaricomOrange,
    onSecondary        = Color.White,
    secondaryContainer = Color(0xFFFFEDD5),
    onSecondaryContainer = Color(0xFF3A1500),
    tertiary           = MpesaAccentGreen,
    onTertiary         = Color.White,
    background         = SurfaceLight,
    onBackground       = Color(0xFF0D1F0D),
    surface            = CardLight,
    onSurface          = Color(0xFF1A2E1A),
    surfaceVariant     = Color(0xFFE3F5E0),
    onSurfaceVariant   = Color(0xFF3A5C3A),
    outline            = DividerLight,
    error              = ErrorRed,
    onError            = Color.White
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
            window.statusBarColor = if (darkTheme) android.graphics.Color.parseColor("#0D1F0D") else android.graphics.Color.parseColor("#2E7D32")
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
