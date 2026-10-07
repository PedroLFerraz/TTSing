package com.pedrolopes.ttsing.ui.news

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

private val UrlPattern = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

/** Where the web addresses in [text] are, less the punctuation that follows them ("see https://x.com/a."). */
internal fun urlRanges(text: String): List<IntRange> = UrlPattern.findAll(text).mapNotNull { match ->
    var url = match.value
    while (url.isNotEmpty()) {
        val last = url.last()
        // A closing bracket belongs to the address only when it closes one the address opened.
        val strip = last in ".,;:!?" || (last == ')' && url.count { it == ')' } > url.count { it == '(' })
        if (!strip) break
        url = url.dropLast(1)
    }
    (match.range.first..match.range.first + url.length - 1).takeIf { url.substringAfter("://").isNotEmpty() }
}.toList()

/** [text] with its web addresses made tappable. */
internal fun linkified(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    val style = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    for (range in urlRanges(text)) {
        append(text.substring(cursor, range.first))
        val url = text.substring(range.first, range.last + 1)
        withLink(LinkAnnotation.Url(url, style)) { append(url) }
        cursor = range.last + 1
    }
    append(text.substring(cursor))
}
