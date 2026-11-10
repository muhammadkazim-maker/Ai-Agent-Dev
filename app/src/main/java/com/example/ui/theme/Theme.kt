package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val OmniVoiceDarkColorScheme = darkColorScheme(
    primary = CyanPrimary,
    onPrimary = ObsidianCanvas,
    primaryContainer = CyanContainerDark,
    onPrimaryContainer = OnCyanContainerDark,
    secondary = AmberWarning,
    onSecondary = ObsidianCanvas,
    secondaryContainer = AmberContainerDark,
    onSecondaryContainer = OnAmberContainerDark,
    tertiary = EmeraldVerified,
    onTertiary = ObsidianCanvas,
    tertiaryContainer = EmeraldContainerDark,
    onTertiaryContainer = AlabasterText,
    error = CrimsonStop,
    onError = Color.White,
    errorContainer = CrimsonContainerDark,
    onErrorContainer = OnCrimsonContainerDark,
    background = ObsidianCanvas,
    onBackground = AlabasterText,
    surface = SlateDeckSurface,
    onSurface = AlabasterText,
    surfaceVariant = ElevatedChamberSurface,
    onSurfaceVariant = CoolPewterText,
    outline = GlassBorderSubtle
)

private val OmniVoiceLightColorScheme = lightColorScheme(
    primary = CyanPrimaryLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF0C4A6E),
    secondary = AmberWarningLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFEF3C7),
    onSecondaryContainer = Color(0xFF78350F),
    tertiary = EmeraldVerified,
    onTertiary = Color.White,
    error = CrimsonStopLight,
    onError = Color.White,
    errorContainer = Color(0xFFFFE4E6),
    onErrorContainer = Color(0xFF881337),
    background = DaylightCanvas,
    onBackground = DeepInkText,
    surface = DaylightSurface,
    onSurface = DeepInkText,
    surfaceVariant = DaylightChamber,
    onSurfaceVariant = SlateBodyText,
    outline = DaylightBorder
)

@Composable
fun OmniVoiceRebuildTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) OmniVoiceDarkColorScheme else OmniVoiceLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
