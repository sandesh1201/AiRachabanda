package com.airachabanda.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.airachabanda.app.core.model.AppearanceMode

private val Navy = Color(0xFF10233F)
private val Indigo = Color(0xFF3D4DB8)
private val Violet = Color(0xFF6D4AFF)
private val Paper = Color(0xFFF4F6FB)
private val PaperDark = Color(0xFF0C1424)
private val SurfaceDark = Color(0xFF162033)

private val LightColors = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Navy,
    secondary = Indigo,
    onSecondary = Color.White,
    tertiary = Violet,
    onTertiary = Color.White,
    background = Paper,
    onBackground = Color(0xFF121826),
    surface = Color.White,
    onSurface = Color(0xFF121826),
    surfaceVariant = Color(0xFFE4E8F4),
    onSurfaceVariant = Color(0xFF3C4660),
    error = Color(0xFF8C2F2F),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9C6FF),
    onPrimary = Color(0xFF0C1730),
    primaryContainer = Color(0xFF24345C),
    onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFFC3C5FF),
    onSecondary = Color(0xFF1B2148),
    tertiary = Color(0xFFD4C4FF),
    onTertiary = Color(0xFF2A1460),
    background = PaperDark,
    onBackground = Color(0xFFE7EAF6),
    surface = SurfaceDark,
    onSurface = Color(0xFFE7EAF6),
    surfaceVariant = Color(0xFF2A3550),
    onSurfaceVariant = Color(0xFFC5CCE0),
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
