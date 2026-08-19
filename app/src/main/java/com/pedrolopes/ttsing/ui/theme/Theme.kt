package com.pedrolopes.ttsing.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * One scheme, always black. The reader's own theme (light / sepia / black) still governs the
 * page you read on — see [com.pedrolopes.ttsing.ui.reader.readerPalette] — but the app around
 * it does not follow the system, because half the design is the black it sits on.
 */
private val BlackScheme = darkColorScheme(
    primary = Ink.Live,
    onPrimary = Ink.Surface,
    primaryContainer = Ink.Live,
    onPrimaryContainer = Ink.Surface,
    secondary = Ink.Live,
    onSecondary = Ink.Surface,
    secondaryContainer = Ink.Well,
    onSecondaryContainer = Ink.Text,
    tertiary = Ink.Muted,
    background = Ink.Surface,
    onBackground = Ink.Text,
    surface = Ink.Surface,
    onSurface = Ink.Text,
    surfaceVariant = Ink.Raised,
    onSurfaceVariant = Ink.Muted,
    surfaceContainer = Ink.Surface,
    surfaceContainerHigh = Ink.Surface,
    surfaceContainerHighest = Ink.Raised,
    surfaceContainerLow = Ink.Surface,
    surfaceContainerLowest = Ink.Surface,
    outline = Ink.Edge,
    outlineVariant = Ink.Hairline,
    error = Color(0xFFFF6B57),
    onError = Ink.Surface,
    errorContainer = Color(0xFF2A0F0A),
    onErrorContainer = Color(0xFFFFB4A6),
    scrim = Color(0xCC000000),
)

/** Space Grotesk everywhere Material would otherwise reach for the platform sans. */
private val TTSingTypography = Typography().let { base ->
    fun TextStyle.grotesk() = copy(fontFamily = AppFonts.Grotesk)
    Typography(
        displayLarge = base.displayLarge.grotesk(),
        displayMedium = base.displayMedium.grotesk(),
        displaySmall = base.displaySmall.grotesk(),
        headlineLarge = base.headlineLarge.grotesk(),
        headlineMedium = base.headlineMedium.grotesk(),
        headlineSmall = base.headlineSmall.grotesk(),
        titleLarge = base.titleLarge.grotesk().copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.01).em),
        titleMedium = base.titleMedium.grotesk().copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.grotesk().copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.grotesk(),
        bodyMedium = base.bodyMedium.grotesk(),
        bodySmall = base.bodySmall.grotesk(),
        // Labels are the mono voice of the design — anything Material renders as a label
        // (chips, sliders' value text, small captions) picks it up automatically.
        labelLarge = TextStyle(fontFamily = AppFonts.Mono, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.14.em),
        labelMedium = TextStyle(fontFamily = AppFonts.Mono, fontSize = 11.sp, letterSpacing = 0.16.em),
        labelSmall = TextStyle(fontFamily = AppFonts.Mono, fontSize = 10.sp, letterSpacing = 0.16.em),
    )
}

/** Hard edges everywhere; the only rounded thing in the design is the primary action pill. */
private val SquareShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp),
)

@Composable
fun TTSingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BlackScheme,
        typography = TTSingTypography,
        shapes = SquareShapes,
        content = content,
    )
}
