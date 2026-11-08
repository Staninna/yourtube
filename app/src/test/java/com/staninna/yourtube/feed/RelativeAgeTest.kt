package com.staninna.yourtube.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelativeAgeTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour
    private val now = 1_800_000_000_000L

    @Test fun parsesYouTubesAgesWithAndWithoutPrefixes() {
        assertEquals(30_000L, RelativeAge.parse("30 seconds ago"))
        assertEquals(minute, RelativeAge.parse("1 minute ago"))
        assertEquals(3 * hour, RelativeAge.parse("3 hours ago"))
        assertEquals(9 * day, RelativeAge.parse("9 days ago"))
        assertEquals(14 * day, RelativeAge.parse("2 weeks ago"))
        assertEquals(120 * day, RelativeAge.parse("4 months ago"))
        assertEquals(365 * day, RelativeAge.parse("1 year ago"))
        assertEquals(2 * day, RelativeAge.parse("Streamed 2 days ago"))
        assertEquals(7 * day, RelativeAge.parse("Premiered 1 week ago"))
    }

    @Test fun anythingElseIsNoAge() {
        assertNull(RelativeAge.parse(""))
        assertNull(RelativeAge.parse("Scheduled for 11/12/26"))
        assertNull(RelativeAge.parse("vor 2 Tagen"))
        assertNull(RelativeAge.parse("2 fortnights ago"))
    }

    @Test fun countsPastAThousandAreNoAgeAndNeverOverflow() {
        assertEquals(1000 * 365 * day, RelativeAge.parse("1000 years ago"))
        assertNull(RelativeAge.parse("1001 days ago"))
        assertNull(RelativeAge.parse("9223372036854775807 years ago"))
        assertNull(RelativeAge.parse("99999999999999999999 years ago"))
    }

    @Test fun formatsLikeYouTube() {
        assertEquals("Just now", RelativeAge.format(now - 20_000, now))
        assertEquals("1 minute ago", RelativeAge.format(now - minute, now))
        assertEquals("59 minutes ago", RelativeAge.format(now - 59 * minute, now))
        assertEquals("1 hour ago", RelativeAge.format(now - hour, now))
        assertEquals("23 hours ago", RelativeAge.format(now - 23 * hour - 59 * minute, now))
        assertEquals("1 day ago", RelativeAge.format(now - day, now))
        assertEquals("13 days ago", RelativeAge.format(now - 13 * day, now))
        assertEquals("2 weeks ago", RelativeAge.format(now - 14 * day, now))
        assertEquals("4 weeks ago", RelativeAge.format(now - 29 * day, now))
        assertEquals("1 month ago", RelativeAge.format(now - 30 * day, now))
        assertEquals("12 months ago", RelativeAge.format(now - 364 * day, now))
        assertEquals("2 years ago", RelativeAge.format(now - 800 * day, now))
        assertEquals("a clock running behind is not the future", "Just now", RelativeAge.format(now + hour, now))
    }

    @Test fun aPageAgeReadsBackAsYouTubeWroteIt() {
        listOf("5 minutes ago", "1 hour ago", "9 days ago", "2 weeks ago", "4 weeks ago", "1 month ago", "11 months ago", "3 years ago")
            .forEach { assertEquals(it, RelativeAge.format(now - RelativeAge.parse(it)!!, now)) }
    }
}
