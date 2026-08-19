package com.pedrolopes.ttsing.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pedrolopes.ttsing.ui.theme.HazardStripe
import com.pedrolopes.ttsing.ui.theme.Ink
import com.pedrolopes.ttsing.ui.theme.monoLabel
import com.pedrolopes.ttsing.ui.theme.wordmark

/** The horizontal margin every screen's content lines up to. */
val ScreenPadding: Dp = 22.dp

/**
 * The header shared by the library and news: a mono title, muted icon actions, and the
 * hazard stripe that separates chrome from content.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = ScreenPadding, end = ScreenPadding - 8.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.invoke()
            Text(
                text = title,
                style = wordmark(size = if (leading == null) 22f else 20f),
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically) { actions() }
        }
        HazardStripe(modifier = Modifier.padding(start = ScreenPadding, end = ScreenPadding, top = 12.dp))
    }
}

/**
 * The one-line census under a header — "LIBRARY · 6 BOOKS · 1 IN PROGRESS". [highlight] is
 * the segment that carries state, and is the only part drawn in the live colour.
 */
@Composable
fun StatusStrip(
    parts: List<String>,
    highlightIndex: Int = -1,
    modifier: Modifier = Modifier,
) {
    Text(
        text = buildAnnotatedString {
            parts.forEachIndexed { index, part ->
                if (index > 0) append(" · ")
                if (index == highlightIndex) {
                    withStyle(SpanStyle(color = Ink.Live)) { append(part.uppercase()) }
                } else {
                    append(part.uppercase())
                }
            }
        },
        style = monoLabel(size = 11.5f, tracking = 0.18f),
        maxLines = 1,
        modifier = modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
    )
}

/** A one-pixel rule inset to the screen margin, the way the design separates rows. */
@Composable
fun Hairline(modifier: Modifier = Modifier, inset: Dp = ScreenPadding, color: Color = Ink.Hairline) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = inset)
            .height(1.dp)
            .background(color),
    )
}

/** The single rounded shape in the design: the primary action. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(if (enabled) Ink.Live else Ink.Raised, RoundedCornerShape(999.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.uppercase(),
            style = monoLabel(
                size = 13f,
                tracking = 0.16f,
                weight = FontWeight.Bold,
                color = if (enabled) Ink.Surface else Ink.Dim,
            ),
        )
    }
}

/** Square-cornered, three-pixel: the progress bar used for books, chapters and pages. */
@Composable
fun ThinProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = Ink.Live,
    track: Color = Ink.Track,
    height: Dp = 3.dp,
) {
    Box(modifier = modifier.fillMaxWidth().height(height).background(track)) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(height)
                .background(color),
        )
    }
}

/** A header action: 20dp, muted, no ripple-heavy Material padding around it. */
@Composable
fun HeaderIcon(
    icon: ImageVector,
    contentDescription: String,
    tint: Color = Ink.Muted,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier.size(44.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** Uppercase mono metadata, the design's default way of saying anything small. */
@Composable
fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    size: Float = 10f,
    tracking: Float = 0.14f,
    color: Color = Ink.Muted,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1,
    uppercase: Boolean = true,
) {
    Text(
        text = if (uppercase) text.uppercase() else text,
        style = monoLabel(size = size, tracking = tracking, weight = weight, color = color),
        maxLines = maxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Same, when the caller has already coloured parts of the string. */
@Composable
fun MonoText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    size: Float = 10f,
    tracking: Float = 0.14f,
    color: Color = Ink.Muted,
    maxLines: Int = 1,
) {
    Text(
        text = text,
        style = monoLabel(size = size, tracking = tracking, color = color),
        maxLines = maxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Arrangement helper so screens can declare their gaps in one place. */
val RowGap = Arrangement.spacedBy(14.dp)
