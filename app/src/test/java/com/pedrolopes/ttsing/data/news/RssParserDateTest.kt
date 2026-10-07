package com.pedrolopes.ttsing.data.news

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** The date shapes feeds really use. An unreadable one is stored as "first seen now", see NewsRepository. */
class RssParserDateTest {

    private val expected = Instant.parse("2024-03-13T15:04:05Z").toEpochMilli()

    private fun assertParses(text: String, millis: Long = expected) =
        assertEquals(text, millis, RssParser.parseDate(text))

    @Test
    fun `rfc 822 with a numeric offset, with and without a colon`() {
        assertParses("Wed, 13 Mar 2024 15:04:05 +0000")
        assertParses("Wed, 13 Mar 2024 17:04:05 +0200")
        assertParses("Wed, 13 Mar 2024 12:04:05 -0300")
        assertParses("Wed, 13 Mar 2024 12:04:05 -03:00")
    }

    @Test
    fun `rfc 822 with a zone name`() {
        assertParses("Wed, 13 Mar 2024 15:04:05 GMT")
        assertParses("Wed, 13 Mar 2024 15:04:05 UT")
        assertParses("Wed, 13 Mar 2024 08:04:05 PDT")
    }

    @Test
    fun `rfc 822 without a zone is taken as UTC`() {
        assertParses("Wed, 13 Mar 2024 15:04:05")
        assertParses("13 Mar 2024 15:04:05")
    }

    @Test
    fun `without a weekday, or with the wrong one`() {
        assertParses("13 Mar 2024 15:04:05 +0000")
        assertParses("13 Mar 2024 15:04:05 GMT")
        // 13 March 2024 was a Wednesday.
        assertParses("Tue, 13 Mar 2024 15:04:05 +0000")
        assertParses("Wednesday, 13 Mar 2024 15:04:05 +0000")
    }

    @Test
    fun `single digit day and two digit year`() {
        assertParses("Wed, 3 Jan 2024 15:04:05 +0000", Instant.parse("2024-01-03T15:04:05Z").toEpochMilli())
        assertParses("Wed, 13 Mar 24 15:04:05 +0000")
    }

    @Test
    fun `iso 8601 in its usual forms`() {
        assertParses("2024-03-13T15:04:05Z")
        assertParses("2024-03-13T15:04:05+00:00")
        assertParses("2024-03-13T12:04:05-03:00")
        assertParses("2024-03-13T15:04:05.000Z")
        assertParses("2024-03-13T15:04:05.123+00:00", expected + 123)
    }

    @Test
    fun `iso 8601 with an offset but no colon, or no offset at all`() {
        assertParses("2024-03-13T15:04:05+0000")
        assertParses("2024-03-13T12:04:05-0300")
        assertParses("2024-03-13T15:04:05")
        assertParses("2024-03-13T15:04:05.000")
        assertParses("2024-03-13 15:04:05")
        assertParses("2024-03-13 15:04:05 +0000")
    }

    @Test
    fun `iso 8601 with more than three fraction digits`() {
        assertParses("2024-03-13T15:04:05.123456Z", expected + 123)
        assertParses("2024-03-13T15:04:05.123456789+00:00", expected + 123)
    }

    @Test
    fun `a bare date is midnight UTC`() {
        assertEquals(Instant.parse("2024-03-13T00:00:00Z").toEpochMilli(), RssParser.parseDate("2024-03-13"))
    }

    @Test
    fun `day first with slashes`() {
        assertParses("13/03/2024 15:04:05")
    }

    @Test
    fun `what cannot be read is zero`() {
        assertEquals(0L, RssParser.parseDate(null))
        assertEquals(0L, RssParser.parseDate("  "))
        assertEquals(0L, RssParser.parseDate("yesterday"))
        assertEquals(0L, RssParser.parseDate("2024-13-45"))
    }
}
