package com.gimso2x.herdr.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Warm palette adapted from ompgui ui/RemoteTheme.kt (OmpColors, warm variant).
private object Warm {
    val DarkBg = Color(0xFF1B1916)
    val DarkPanel = Color(0xFF231F1B)
    val DarkHover = Color(0xFF2B2721)
    val DarkSelected = Color(0xFF332E26)
    val DarkBorder = Color(0xFF38322B)
    val DarkText = Color(0xFFEBE6DC)
    val DarkMuted = Color(0xFFA39B8E)
    val DarkDim = Color(0xFF938C81)
    val DarkAccentStrong = Color(0xFFC2542E)
    val DarkError = Color(0xFFFF8A80)

    val LightBg = Color(0xFFFAF9F6)
    val LightPanel = Color(0xFFF2F0EA)
    val LightHover = Color(0xFFEAE7DF)
    val LightSelected = Color(0xFFE5E0D4)
    val LightBorder = Color(0xFFE2DDD2)
    val LightText = Color(0xFF2B2823)
    val LightMuted = Color(0xFF69635A)
    val LightDim = Color(0xFF6A6458)
    val LightAccentStrong = Color(0xFFB03E22)
    val LightError = Color(0xFFB42318)
}

private val DarkScheme = darkColorScheme(
    primary = Warm.DarkAccentStrong, onPrimary = Color.White,
    primaryContainer = Warm.DarkSelected, onPrimaryContainer = Warm.DarkText,
    secondary = Warm.DarkAccentStrong, onSecondary = Color.White,
    secondaryContainer = Warm.DarkSelected, onSecondaryContainer = Warm.DarkText,
    tertiary = Warm.DarkAccentStrong, onTertiary = Color.White,
    background = Warm.DarkBg, onBackground = Warm.DarkText,
    surface = Warm.DarkPanel, onSurface = Warm.DarkText,
    surfaceVariant = Warm.DarkHover, onSurfaceVariant = Warm.DarkMuted,
    surfaceTint = Color.Transparent,
    outline = Warm.DarkDim, outlineVariant = Warm.DarkBorder,
    error = Warm.DarkError, onError = Warm.DarkBg,
    errorContainer = lerp(Warm.DarkBg, Warm.DarkError, 0.12f), onErrorContainer = Warm.DarkText,
)

private val LightScheme = lightColorScheme(
    primary = Warm.LightAccentStrong, onPrimary = Color.White,
    primaryContainer = Warm.LightSelected, onPrimaryContainer = Warm.LightText,
    secondary = Warm.LightAccentStrong, onSecondary = Color.White,
    secondaryContainer = Warm.LightSelected, onSecondaryContainer = Warm.LightText,
    tertiary = Warm.LightAccentStrong, onTertiary = Color.White,
    background = Warm.LightBg, onBackground = Warm.LightText,
    surface = Warm.LightPanel, onSurface = Warm.LightText,
    surfaceVariant = Warm.LightHover, onSurfaceVariant = Warm.LightMuted,
    surfaceTint = Color.Transparent,
    outline = Warm.LightDim, outlineVariant = Warm.LightBorder,
    error = Warm.LightError, onError = Warm.LightBg,
    errorContainer = lerp(Warm.LightBg, Warm.LightError, 0.12f), onErrorContainer = Warm.LightText,
)

private val HerdrTypography = Typography(
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
)

private val HerdrShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

@Composable
fun HerdrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        typography = HerdrTypography,
        shapes = HerdrShapes,
        content = content,
    )
}
