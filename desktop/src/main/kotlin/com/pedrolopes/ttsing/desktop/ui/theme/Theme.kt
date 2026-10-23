package com.pedrolopes.ttsing.desktop.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/*
 * The Android app's look (ui/theme there): pure black, one yellow for whatever is live, a short
 * grey ramp for the rest, and three typefaces with a job each.
 */

object Ink {
    val Surface = Color(0xFF000000)
    val Raised = Color(0xFF0E0E0E)
    val Well = Color(0xFF101010)

    /** Reserved for state: playing, in progress, the primary action. */
    val Live = Color(0xFFFFD400)

    /** The same accent for light surfaces, where #FFD400 has no contrast. */
    val LiveOnPaper = Color(0xFF856100)

    val Text = Color(0xFFFFFFFF)
    val ReaderText = Color(0xFFE8E8E4)
    val Muted = Color(0xFF8A8A84)
    val Dim = Color(0xFF6E6E68)
    val Faint = Color(0xFF55554F)

    val Hairline = Color(0x24FFFFFF)
    val Edge = Color(0x2EFFFFFF)
    val Track = Color(0x24FFFFFF)
}

/** Grotesk for the interface, mono for labels and metadata, serif for the book itself. */
object AppFonts {
    val Grotesk = FontFamily(
        Font("fonts/space_grotesk_regular.ttf", FontWeight.Normal),
        Font("fonts/space_grotesk_medium.ttf", FontWeight.Medium),
        Font("fonts/space_grotesk_semibold.ttf", FontWeight.SemiBold),
        Font("fonts/space_grotesk_bold.ttf", FontWeight.Bold),
    )
    val Mono = FontFamily(
        Font("fonts/jetbrains_mono_regular.ttf", FontWeight.Normal),
        Font("fonts/jetbrains_mono_medium.ttf", FontWeight.Medium),
        Font("fonts/jetbrains_mono_bold.ttf", FontWeight.Bold),
    )
    val Serif = FontFamily(
        Font("fonts/source_serif_regular.ttf", FontWeight.Normal),
        Font("fonts/source_serif_semibold.ttf", FontWeight.SemiBold),
        Font("fonts/source_serif_semibold.ttf", FontWeight.Bold),
    )
}

/** The uppercase mono label the design uses for every piece of metadata. */
fun monoLabel(
    size: Float = 11f,
    tracking: Float = 0.18f,
    weight: FontWeight = FontWeight.Normal,
    color: Color = Ink.Muted,
): TextStyle = TextStyle(
    fontFamily = AppFonts.Mono,
    fontSize = size.sp,
    fontWeight = weight,
    letterSpacing = tracking.em,
    color = color,
)

fun wordmark(size: Float = 22f, color: Color = Ink.Text): TextStyle = TextStyle(
    fontFamily = AppFonts.Mono,
    fontSize = size.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 0.16.em,
    color = color,
)

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
    surfaceContainer = Ink.Raised,
    surfaceContainerHigh = Ink.Raised,
    surfaceContainerHighest = Ink.Raised,
    surfaceContainerLow = Ink.Surface,
    surfaceContainerLowest = Ink.Surface,
    outline = Ink.Edge,
    outlineVariant = Ink.Hairline,
    error = Color(0xFFFF6B57),
    onError = Ink.Surface,
    scrim = Color(0xCC000000),
)

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
        labelLarge = TextStyle(fontFamily = AppFonts.Mono, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.14.em),
        labelMedium = TextStyle(fontFamily = AppFonts.Mono, fontSize = 11.sp, letterSpacing = 0.16.em),
        labelSmall = TextStyle(fontFamily = AppFonts.Mono, fontSize = 10.sp, letterSpacing = 0.16.em),
    )
}

/** Hard edges everywhere. */
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

/** The diagonal yellow/black band under the library's header: what makes the black screen this app's. */
@Composable
fun HazardStripe(modifier: Modifier = Modifier, height: Dp = 8.dp, band: Dp = 14.dp, gap: Dp = 10.dp) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier.fillMaxWidth().height(height)) {
        val period = with(density) { (band + gap).toPx() }
        val bandShare = band / (band + gap)
        val brush = Brush.linearGradient(
            0f to Ink.Live,
            bandShare to Ink.Live,
            bandShare to Ink.Surface,
            1f to Ink.Surface,
            start = Offset.Zero,
            end = Offset(period * 0.906f, period * 0.423f),
            tileMode = TileMode.Repeated,
        )
        Box(modifier = Modifier.fillMaxSize().background(brush))
    }
}
