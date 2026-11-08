package com.staninna.yourtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeTest {

    @Test fun searchUrlEncodesTheTrimmedQuery() {
        assertEquals("https://m.youtube.com/results?search_query=lofi+girl", YouTube.searchUrl("  lofi girl "))
        assertEquals(
            "https://m.youtube.com/results?search_query=c%2B%2B+%26+r%C3%BCst%3F",
            YouTube.searchUrl("c++ & rüst?"),
        )
    }

    @Test fun blankQueriesDoNotSearch() {
        assertNull(YouTube.searchUrl(""))
        assertNull(YouTube.searchUrl("   "))
    }

    @Test fun searchQueryReadsBackWhatSearchUrlWrote() {
        val query = "c++ & rüst?"
        assertEquals(query, YouTube.searchQuery(YouTube.searchUrl(query)))
        assertEquals("cats", YouTube.searchQuery("https://www.youtube.com/results?sp=EgIQAQ%3D%3D&search_query=cats"))
    }

    @Test fun searchQueryIsNullOffAResultsPage() {
        assertNull(YouTube.searchQuery(null))
        assertNull(YouTube.searchQuery("https://m.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(YouTube.searchQuery("https://example.com/results?search_query=cats"))
        assertNull(YouTube.searchQuery("https://m.youtube.com/results?search_query="))
        assertNull(YouTube.searchQuery("::not a url"))
    }

    @Test fun sharedLinksUseTheDesktopHost() {
        assertEquals("https://www.youtube.com/watch?v=x&t=42", YouTube.shareUrl("https://m.youtube.com/watch?v=x&t=42"))
        assertEquals("https://www.youtube.com/@veritasium", YouTube.shareUrl("https://www.youtube.com/@veritasium"))
    }

    @Test fun pageBackgroundFollowsTheTheme() {
        assertEquals(0xFF0F0F0F.toInt(), YouTube.pageBackground(dark = true))
        assertEquals(0xFFFFFFFF.toInt(), YouTube.pageBackground(dark = false))
    }
}
