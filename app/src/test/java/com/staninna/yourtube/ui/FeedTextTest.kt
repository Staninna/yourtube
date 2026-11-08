package com.staninna.yourtube.ui

import com.staninna.yourtube.data.FeedVideo
import com.staninna.yourtube.data.VideoLength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedTextTest {

    @Test fun theFailureNoticeNamesOneOrTwoChannelsAndCountsMore() {
        assertEquals("Veritasium didn't load.", feedFailureNotice(listOf("Veritasium"), offline = false))
        assertEquals("A and B didn't load.", feedFailureNotice(listOf("A", "B"), offline = false))
        assertEquals("A and 3 other channels didn't load.", feedFailureNotice(listOf("A", "B", "C", "D"), offline = false))
        assertEquals("You're offline. A didn't load.", feedFailureNotice(listOf("A"), offline = true))
    }

    @Test fun viewCountsReadLikeYouTubes() {
        // The pairs are a real videos page's exact and short counts (8 November 2026).
        mapOf(
            1_742_245L to "1.7M views", 2_066_966L to "2M views", 1_050_430L to "1M views", 367_973L to "367K views",
            42_851L to "42K views", 5_289_215L to "5.2M views", 668_023_722L to "668M views",
        ).forEach { (count, text) -> assertEquals(text, formatViews(count)) }
        assertEquals("No views", formatViews(0))
        assertEquals("1 view", formatViews(1))
        assertEquals("999 views", formatViews(999))
        assertEquals("1K views", formatViews(1_000))
        assertEquals("1.2B views", formatViews(1_234_567_890))
    }

    private fun video(length: VideoLength? = null, short: Boolean = false, views: Long? = null) =
        FeedVideo("aaaaaaaaaaa", "T", "A", "UC", 0, isShort = short, views = views, length = length)

    @Test fun theBadgeIsTheLengthOrWhatStandsInForIt() {
        assertEquals("12:34", feedBadge(video(VideoLength.Known(754))))
        assertEquals("1:02:09", feedBadge(video(VideoLength.Known(3729))))
        assertEquals("LIVE", feedBadge(video(VideoLength.Live(1))))
        assertEquals("SHORT", feedBadge(video(short = true)))
        assertNull(feedBadge(video(VideoLength.Unlisted(1))))
        assertNull(feedBadge(video()))
    }

    @Test fun theDetailLineIsViewsThenAge() {
        val day = 24 * 60 * 60 * 1000L
        assertEquals("1.2M views · 3 days ago", feedDetail(video(views = 1_234_567), 3 * day))
        assertEquals("3 days ago", feedDetail(video(), 3 * day))
    }
}
