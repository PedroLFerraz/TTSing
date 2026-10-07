package com.pedrolopes.ttsing.data.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A YouTube channel feed, trimmed to the parts that matter, as YouTube serves it. */
class YouTubeFeedTest {

    private val feed = """<?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns:media="http://search.yahoo.com/mrss/" xmlns="http://www.w3.org/2005/Atom">
         <link rel="self" href="http://www.youtube.com/feeds/videos.xml?channel_id=UCHnyfMqiRRG1u-2MsSQLbXA"/>
         <id>yt:channel:HnyfMqiRRG1u-2MsSQLbXA</id>
         <title>Veritasium</title>
         <link rel="alternate" href="https://www.youtube.com/channel/UCHnyfMqiRRG1u-2MsSQLbXA"/>
         <entry>
          <id>yt:video:dQw4w9WgXcQ</id>
          <yt:videoId>dQw4w9WgXcQ</yt:videoId>
          <title>Why the sky is blue</title>
          <link rel="alternate" href="https://www.youtube.com/watch?v=dQw4w9WgXcQ"/>
          <published>2026-10-01T15:00:06+00:00</published>
          <media:group>
           <media:title>Why the sky is blue</media:title>
           <media:content url="https://www.youtube.com/v/dQw4w9WgXcQ?version=3" type="application/x-shockwave-flash" width="640" height="390"/>
           <media:thumbnail url="https://i2.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg" width="480" height="360"/>
           <media:description>Rayleigh scattering, explained.
0:00 Intro</media:description>
          </media:group>
         </entry>
        </feed>"""

    @Test
    fun `channel feed parses into a video with thumbnail and description`() {
        val parsed = RssParser.parse(feed)!!
        assertEquals("Veritasium", parsed.title)
        val video = parsed.items.single()
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", video.link)
        assertEquals("https://i2.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", video.imageUrl)
        assertEquals("Rayleigh scattering, explained.\n0:00 Intro", video.summary)
        assertEquals("dQw4w9WgXcQ", YouTube.videoId(video.link))
    }

    @Test
    fun `video links are recognised and ordinary ones are not`() {
        assertEquals("dQw4w9WgXcQ", YouTube.videoId("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YouTube.videoId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YouTube.videoId("https://m.youtube.com/watch?feature=share&v=dQw4w9WgXcQ"))
        assertNull(YouTube.videoId("https://www.youtube.com/channel/UCHnyfMqiRRG1u-2MsSQLbXA"))
        assertNull(YouTube.videoId("https://www.theverge.com/watch?v=dQw4w9WgXcQ"))
    }
}
