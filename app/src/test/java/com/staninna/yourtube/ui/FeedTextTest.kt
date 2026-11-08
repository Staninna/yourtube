package com.staninna.yourtube.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedTextTest {

    @Test fun theFailureNoticeNamesOneOrTwoChannelsAndCountsMore() {
        assertEquals("Veritasium didn't load.", feedFailureNotice(listOf("Veritasium"), offline = false))
        assertEquals("A and B didn't load.", feedFailureNotice(listOf("A", "B"), offline = false))
        assertEquals("A and 3 other channels didn't load.", feedFailureNotice(listOf("A", "B", "C", "D"), offline = false))
        assertEquals("You're offline. A didn't load.", feedFailureNotice(listOf("A"), offline = true))
    }
}
