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
private val InkDark = Color(0xFF9FC4E0)
private val Paper = Color(0xFFF6F4EF)
private val PaperDark = Color(0xFF121417)
private val SurfaceDark = Color(0xFF1C2128)
private val Secondary = Color(0xFF5C6B73)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    secondary = Secondary,
    background = Paper,
    surface = Color.White,
    surfaceVariant = Color(0xFFE7E2D8),
)

private val DarkColors = darkColorScheme(
    primary = InkDark,
    onPrimary = Color(0xFF0E1A24),
    secondary = Color(0xFFB7C4CC),
    background = PaperDark,
    surface = SurfaceDark,
    surfaceVariant = Color(0xFF2A313A),
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
