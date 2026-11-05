package com.droidlink.presentation.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.droidlink.domain.model.ThemeMode

// Palette taken from the DroidLink / RemotIQ logo: teal -> blue -> violet on deep navy.
val Teal = Color(0xFF2EE6C5)
val Blue = Color(0xFF3FA9F5)
val Violet = Color(0xFF8B5CF6)
val Navy = Color(0xFF0F1626)
val NavySurface = Color(0xFF172036)
val NavySurfaceHigh = Color(0xFF1E2A45)
val Danger = Color(0xFFFF5C6C)
val Success = Color(0xFF34D399)
val Warning = Color(0xFFFBBF24)

private val DarkColors = darkColorScheme(
    primary = Teal,
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF0B4F45),
    onPrimaryContainer = Color(0xFFB8FFF1),
    secondary = Violet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF3B2A78),
    onSecondaryContainer = Color(0xFFE7DDFF),
    tertiary = Blue,
    background = Navy,
    onBackground = Color(0xFFE6EAF5),
    surface = NavySurface,
    onSurface = Color(0xFFE6EAF5),
    surfaceVariant = NavySurfaceHigh,
    onSurfaceVariant = Color(0xFFB4BCD2),
    error = Danger,
    outline = Color(0xFF55627F),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00806C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8FFF1),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF6B46D6),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7DDFF),
    onSecondaryContainer = Color(0xFF22005D),
    tertiary = Color(0xFF0B6CB5),
    background = Color(0xFFF5F8FF),
    onBackground = Color(0xFF111827),
    surface = Color.White,
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFE8EEFB),
    onSurfaceVariant = Color(0xFF41506B),
    error = Color(0xFFC62839),
    outline = Color(0xFF8793AD),
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

@Composable
fun DroidLinkTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val colors = if (dark) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes, content = content)
}
