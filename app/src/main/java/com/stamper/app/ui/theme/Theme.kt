package com.stamper.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F6B58),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEEE5),
    onPrimaryContainer = Color(0xFF002119),
    secondary = Color(0xFFB87800),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFECB8),
    onSecondaryContainer = Color(0xFF2B1900),
    tertiary = Color(0xFF3E6375),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC2E8FD),
    onTertiaryContainer = Color(0xFF001F2B),
    background = Color(0xFFF5F7F6),
    onBackground = Color(0xFF171D1B),
    surface = Color.White,
    onSurface = Color(0xFF171D1B),
    surfaceVariant = Color(0xFFE7EEEC),
    onSurfaceVariant = Color(0xFF404946),
    outline = Color(0xFF707976)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7CD8C2),
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF005141),
    onPrimaryContainer = Color(0xFF98F4DD),
    secondary = Color(0xFFFFBA38),
    onSecondary = Color(0xFF442B00),
    secondaryContainer = Color(0xFF624000),
    onSecondaryContainer = Color(0xFFFFDEA8),
    tertiary = Color(0xFFA6CCE0),
    onTertiary = Color(0xFF093545),
    tertiaryContainer = Color(0xFF254B5D),
    onTertiaryContainer = Color(0xFFC2E8FD),
    background = Color(0xFF0F1513),
    onBackground = Color(0xFFDFE4E1),
    surface = Color(0xFF161D1B),
    onSurface = Color(0xFFDFE4E1),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBFC9C5)
)

@Composable
fun StamperTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
