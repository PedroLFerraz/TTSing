package com.pedrolopes.ttsing.data.news

/**
 * YouTube channels publish Atom feeds (`youtube.com/feeds/videos.xml?channel_id=…`, which a
 * channel's own page advertises, so pasting the channel address works). Their entries are
 * videos, not stories: there is no text to fetch or read aloud, so they open in a player.
 */
object YouTube {

    /** The video id behind a watch, shorts or youtu.be link; null for anything else. */
    fun videoId(link: String): String? = VIDEO_LINK.find(link)?.groupValues?.get(1)

    private val VIDEO_LINK = Regex(
        """^https?://(?:(?:www\.|m\.)?youtube\.com/(?:watch\?(?:.*&)?v=|shorts/)|youtu\.be/)([\w-]{11})""",
    )
}
