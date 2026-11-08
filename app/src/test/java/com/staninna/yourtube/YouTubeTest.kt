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
        assertEquals("https://www.youtube.com/watch?v=x", YouTube.shareUrl("https://m.youtube.com/watch?v=x&t=42"))
        assertEquals("https://www.youtube.com/@veritasium", YouTube.shareUrl("https://www.youtube.com/@veritasium"))
    }

    @Test fun sharedLinksLeaveYouTubesOwnParametersAndTheStartTimeBehind() {
        assertEquals(
            "https://www.youtube.com/watch?v=7hlTu95nOCw",
            YouTube.shareUrl("https://m.youtube.com/watch?v=7hlTu95nOCw&t=100s&pp=2AFkkAIB"),
        )
        assertEquals(
            "https://www.youtube.com/watch?v=7hlTu95nOCw&list=PL0123456789abcdef",
            YouTube.shareUrl("https://m.youtube.com/watch?v=7hlTu95nOCw&start=30&list=PL0123456789abcdef"),
        )
        assertEquals(
            "https://www.youtube.com/watch?v=7hlTu95nOCw&list=PL0123456789abcdef",
            YouTube.shareUrl("https://m.youtube.com/watch?feature=share&v=7hlTu95nOCw&si=abc&list=PL0123456789abcdef"),
        )
        assertEquals("https://www.youtube.com/results?search_query=cats", YouTube.shareUrl("https://m.youtube.com/results?search_query=cats"))
    }

    @Test fun pageBackgroundFollowsTheTheme() {
        assertEquals(0xFF0F0F0F.toInt(), YouTube.pageBackground(dark = true))
        assertEquals(0xFFFFFFFF.toInt(), YouTube.pageBackground(dark = false))
    }

    @Test fun queryParamDecodesTheFirstValue() {
        assertEquals("a b&c", YouTube.queryParam("https://x.example/p?q=a+b%26c&q=2", "q"))
        assertNull(YouTube.queryParam("https://x.example/p?q=", "q"))
        assertNull(YouTube.queryParam("https://x.example/p?qq=1", "q"))
        assertNull(YouTube.queryParam("::", "q"))
    }

    @Test fun startTimesLeaveTheUrl() {
        assertEquals(
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1",
            YouTube.withoutStartTime("https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=42s&list=PL1&start=3"),
        )
        assertEquals("https://m.youtube.com/watch?v=dQw4w9WgXcQ", YouTube.withoutStartTime("https://m.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("https://m.youtube.com/watch", YouTube.withoutStartTime("https://m.youtube.com/watch?t=1"))
        assertEquals("https://m.youtube.com/watch?v=x#c", YouTube.withoutStartTime("https://m.youtube.com/watch?v=x&t=1#c"))
    }
}
