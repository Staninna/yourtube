package com.staninna.yourtube.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResumeRulesTest {
    private fun at(position: Double, duration: Double) =
        ResumeRules.startAt(HistoryEntry("v", position = position, duration = duration, watchedAt = 0))

    @Test fun rewindsFiveSeconds() = assertEquals(95.0, at(100.0, 600.0)!!, 0.0)

    @Test fun neverRewindsBelowZero() = assertEquals(0.0, at(5.0, 600.0)!!, 0.0)

    @Test fun noResumeUnderFiveSecondsIn() = assertNull(at(4.9, 600.0))

    @Test fun noResumeWithinFifteenSecondsOfTheEnd() {
        assertNull(at(586.0, 600.0))
        assertEquals(580.0, at(585.0, 600.0)!!, 0.0)
    }

    @Test fun noResumeWithoutADuration() = assertNull(at(100.0, 0.0))

    @Test fun noResumeForAnUnwatchedVideo() = assertNull(ResumeRules.startAt(null))
}
