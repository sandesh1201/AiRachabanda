package com.airachabanda.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.airachabanda.app.core.model.AppearanceMode

private val Ink = Color(0xFF1C3D5A)
private val InkDark = Color(0xFFC5D7E8)
private val Paper = Color(0xFFF7F4EF)
private val PaperDark = Color(0xFF101418)
private val SurfaceDark = Color(0xFF1A2128)
private val Secondary = Color(0xFF5C6B73)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    secondary = Secondary,
    onSecondary = Color.White,
    background = Paper,
    onBackground = Color(0xFF1A1C1E),
    surface = Color.White,
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE7E2D8),
    onSurfaceVariant = Color(0xFF3E4A52),
    error = Color(0xFF8C2F2F),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = InkDark,
    onPrimary = Color(0xFF0E1A24),
    secondary = Color(0xFFB7C4CC),
    onSecondary = Color(0xFF1A1C1E),
    background = PaperDark,
    onBackground = Color(0xFFE6E1D8),
    surface = SurfaceDark,
    onSurface = Color(0xFFE6E1D8),
    surfaceVariant = Color(0xFF2A313A),
    onSurfaceVariant = Color(0xFFC4C7CC),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)

@Composable
fun AIRachabandaTheme(
    appearance: AppearanceMode,
    content: @Composable () -> Unit,
) {
    val dark = when (appearance) {
        AppearanceMode.SYSTEM -> isSystemInDarkTheme()
        AppearanceMode.DARK -> true
        AppearanceMode.LIGHT -> false
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
