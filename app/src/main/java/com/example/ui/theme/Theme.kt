package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val ShaderDarkColorScheme = darkColorScheme(
    primary = PrismCyan,
    onPrimary = OnPrismCyan,
    primaryContainer = PrismCyanContainer,
    onPrimaryContainer = Color(0xFFBFF4FF),
    secondary = PrismAmber,
    onSecondary = OnPrismAmber,
    secondaryContainer = PrismAmberContainer,
    onSecondaryContainer = Color(0xFFFFE1A8),
    tertiary = PrismEmerald,
    onTertiary = Color(0xFF00291D),
    tertiaryContainer = PrismEmeraldContainer,
    onTertiaryContainer = Color(0xFFA7F3D0),
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = ObsidianSurface,
    onSurface = TextPrimary,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianSurfaceVariant,
    surfaceContainerHigh = ObsidianSurfaceHigh,
    outline = ObsidianOutline,
    outlineVariant = ObsidianOutlineVariant,
    error = PrismRose,
    onError = Color(0xFF3A0613),
    errorContainer = PrismRoseContainer,
    onErrorContainer = Color(0xFFFFD8DF),
)

private val ShaderLightColorScheme = lightColorScheme(
    primary = StudioLightPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF082F49),
    secondary = StudioLightSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFEF3C7),
    onSecondaryContainer = Color(0xFF451A03),
    tertiary = Color(0xFF059669),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD1FAE5),
    onTertiaryContainer = Color(0xFF022C22),
    background = StudioLightBg,
    onBackground = StudioLightText,
    surface = StudioLightSurface,
    onSurface = StudioLightText,
    surfaceVariant = StudioLightSurfaceVariant,
    onSurfaceVariant = StudioLightTextSecondary,
    outline = StudioLightOutline,
    error = Color(0xFFE11D48),
    onError = Color.White,
)

val ShaderVeilShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun ShaderVeilTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) ShaderDarkColorScheme else ShaderLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = ShaderVeilTypography,
        shapes = ShaderVeilShapes,
        content = content,
    )
}
