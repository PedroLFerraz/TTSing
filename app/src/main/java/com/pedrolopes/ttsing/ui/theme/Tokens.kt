package com.pedrolopes.ttsing.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.pedrolopes.ttsing.R

/**
 * The redesign's palette: pure black surfaces, one saturated yellow reserved for anything
 * live or actionable, and a short grey ramp for everything that is merely present.
 */
object Ink {
    /** Every full-screen surface. Not near-black — black. */
    val Surface = Color(0xFF000000)
    /** Cards, wells and any panel that must read as raised against [Surface]. */
    val Raised = Color(0xFF0E0E0E)
    /** The selected row's fill in the settings sheet — one step above [Raised]. */
    val Well = Color(0xFF101010)

    /** Reserved for state: playing, unread, in progress, the primary action. */
    val Live = Color(0xFFFFD400)
    /**
     * The same accent for light surfaces, where #FFD400 has no contrast. A dark gold, 5.7:1
     * on white and 4.8:1 on sepia: the old #B58900 was 3.2:1 and 2.7:1, too faint for 10sp text.
     */
    val LiveOnPaper = Color(0xFF856100)

    val Text = Color(0xFFFFFFFF)
    /** Body text in the reader — white at full strength is harsh over long stretches. */
    val ReaderText = Color(0xFFE8E8E4)
    val Muted = Color(0xFF8A8A84)
    val Dim = Color(0xFF6E6E68)
    /** Read, finished, disabled — present but done with. */
    val Faint = Color(0xFF55554F)

    val Hairline = Color(0x24FFFFFF)
    val Edge = Color(0x2EFFFFFF)
    val Track = Color(0x24FFFFFF)
    val Handle = Color(0xFF3A3A36)
}

/**
 * Three families, three jobs: grotesk for the interface, mono for labels and metadata,
 * serif for the book itself.
 */
object AppFonts {
    val Grotesk = FontFamily(
        Font(R.font.space_grotesk_regular, FontWeight.Normal),
        Font(R.font.space_grotesk_medium, FontWeight.Medium),
        Font(R.font.space_grotesk_semibold, FontWeight.SemiBold),
        Font(R.font.space_grotesk_bold, FontWeight.Bold),
    )
    val Mono = FontFamily(
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
        Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
    )
    val Serif = FontFamily(
        Font(R.font.source_serif_regular, FontWeight.Normal),
        Font(R.font.source_serif_semibold, FontWeight.SemiBold),
        Font(R.font.source_serif_semibold, FontWeight.Bold),
    )
}

/**
 * The uppercase mono label the design uses for every piece of metadata. Tracking is what
 * makes it read as a label rather than as text, so it is part of the style, not optional.
 */
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

/** The wordmark: mono, bold, widely tracked. */
fun wordmark(size: Float = 22f, color: Color = Ink.Text): TextStyle = TextStyle(
    fontFamily = AppFonts.Mono,
    fontSize = size.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 0.16.em,
    color = color,
)

/** The stripes lean along (cos, sin) of the design's 115deg gradient: mostly across, down to the right. */
private const val STRIPE_COS = 0.906f
private const val STRIPE_SIN = 0.423f

/**
 * The gradient period that makes the stripes repeat a whole number of times across
 * [widthPx], as close to [period] as that allows. [period] is measured along the gradient's
 * axis; across the bar one repeat is wider by 1/cos of the lean. Without this the right edge
 * cut the pattern wherever it happened to fall and ended in a sliver of yellow.
 */
internal fun fittedStripePeriod(widthPx: Float, period: Float): Float {
    if (widthPx <= 0f || period <= 0f) return period
    val acrossBar = period / STRIPE_COS
    val repeats = kotlin.math.round(widthPx / acrossBar).coerceAtLeast(1f)
    return widthPx / repeats * STRIPE_COS
}

/**
 * The diagonal yellow/black band that sits under every screen header — the one piece of
 * pure ornament in the design, and what makes a black screen recognisably this app.
 */
@Composable
fun HazardStripe(modifier: Modifier = Modifier, height: Dp = 10.dp, band: Dp = 14.dp, gap: Dp = 10.dp) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val wanted = with(density) { (band + gap).toPx() }
    val bandShare = band / (band + gap)
    BoxWithConstraints(modifier = modifier.fillMaxWidth().height(height)) {
        val period = fittedStripePeriod(constraints.maxWidth.toFloat(), wanted)
        val bandPx = period * bandShare
        // The gradient's axis has to be exactly one period long for the stops to land where the
        // band and gap widths say they should — hence a unit vector scaled by the period, rather
        // than a diagonal whose length is the period times root two.
        val brush = Brush.linearGradient(
            0f to Ink.Live,
            (bandPx / period) to Ink.Live,
            (bandPx / period) to Ink.Surface,
            1f to Ink.Surface,
            start = Offset.Zero,
            end = Offset(period * STRIPE_COS, period * STRIPE_SIN),
            tileMode = TileMode.Repeated,
        )
        Box(modifier = Modifier.fillMaxSize().background(brush))
    }
}
