package com.pedrolopes.ttsing.ui.news

import com.pedrolopes.ttsing.data.news.RefreshResult
import com.pedrolopes.ttsing.data.news.db.FeedEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewsViewModelsTest {

    private fun feed(url: String, folder: String?) = FeedEntity(url, url, null, null, folder = folder)

    @Test
    fun `groups that differ only in case are one chip`() {
        val feeds = listOf(feed("a", "Science"), feed("b", "science"), feed("c", "Tech"), feed("d", null))
        assertEquals(listOf("Science", "Tech"), groupsOf(feeds))
    }

    @Test
    fun `a group just created is not dropped before its feeds show up`() {
        // "Cooking" is selected right after it is made; the list of groups hasn't gained it yet.
        assertEquals("Cooking", groupAfterChange("Cooking", previous = listOf("Science"), current = listOf("Science")))
    }

    @Test
    fun `the filter is dropped when its group disappears`() {
        assertNull(groupAfterChange("Science", previous = listOf("Science", "Tech"), current = listOf("Tech")))
        // ... whatever the case of the name.
        assertNull(groupAfterChange("science", previous = listOf("Science"), current = emptyList()))
    }

    @Test
    fun `the filter stays while its group exists`() {
        assertEquals("Tech", groupAfterChange("Tech", listOf("Science", "Tech"), listOf("Science", "Tech")))
        assertEquals("tech", groupAfterChange("tech", listOf("Tech"), listOf("Tech", "Zoo")))
        assertNull(groupAfterChange(null, listOf("Tech"), emptyList()))
    }

    @Test
    fun `an empty list blames the connection when the refresh failed`() {
        val offline = RefreshResult(failedFeeds = 2, firstError = "Unable to resolve host")
        assertEquals(
            "Couldn't load stories — check your connection, then refresh.",
            emptyText(group = null, singleFeed = true, lastRefresh = offline),
        )
    }

    @Test
    fun `an empty list is just empty when the refresh went through`() {
        assertEquals("Nothing here yet — this feed has no stories.", emptyText(null, true, RefreshResult()))
        assertEquals("No stories in Tech yet.", emptyText("Tech", false, RefreshResult()))
        assertEquals("Nothing here yet.", emptyText(null, false, null))
        // Some feeds failing but new stories arriving is not an offline empty state.
        assertEquals("Nothing here yet.", emptyText(null, false, RefreshResult(newStories = 1, failedFeeds = 1)))
    }
}
