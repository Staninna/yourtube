package com.staninna.tube.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure mapping logic between the WebView reports and the stored models. No Android needed. */
class MappingTest {

    @Test fun onlyRealContentIsStarrable() {
        assertTrue(PageInfo(type = "video").starrable)
        assertTrue(PageInfo(type = "channel").starrable)
        assertTrue(PageInfo(type = "playlist").starrable)
        assertFalse(PageInfo(type = "other").starrable)
        assertFalse(PageInfo().starrable)
    }

    @Test fun pageTypeMapsToItemType() {
        assertEquals(ItemType.VIDEO, PageInfo(type = "video", id = "x").toStarredItem().type)
        assertEquals(ItemType.CHANNEL, PageInfo(type = "channel", id = "x").toStarredItem().type)
        assertEquals(ItemType.PLAYLIST, PageInfo(type = "playlist", id = "x").toStarredItem().type)
        // Anything unexpected is treated as a video rather than dropped.
        assertEquals(ItemType.VIDEO, PageInfo(type = "mystery", id = "x").toStarredItem().type)
    }

    @Test fun videoWithoutThumbnailFallsBackToYouTubeStill() {
        val item = PageInfo(type = "video", id = "abc123").toStarredItem()
        assertEquals("https://i.ytimg.com/vi/abc123/hqdefault.jpg", item.thumbnail)
    }

    @Test fun channelWithoutThumbnailHasNone() {
        assertEquals("", PageInfo(type = "channel", id = "UC1").toStarredItem().thumbnail)
    }

    @Test fun blankTitleFallsBackToUrl() {
        val url = "https://m.youtube.com/watch?v=abc123"
        val item = PageInfo(type = "video", id = "abc123", url = url, title = "").toStarredItem()
        assertEquals(url, item.title)
    }

    @Test fun watchReportKeepsGivenUrl() {
        val report = WatchReport(videoId = "abc123", url = "https://m.youtube.com/watch?v=abc123&t=30")
        assertEquals("https://m.youtube.com/watch?v=abc123&t=30", report.toHistoryEntry().url)
    }

    @Test fun watchReportWithoutUrlBuildsWatchUrl() {
        val entry = WatchReport(videoId = "abc123").toHistoryEntry()
        assertEquals("https://m.youtube.com/watch?v=abc123", entry.url)
        assertEquals("abc123", entry.videoId)
    }
}
