package com.staninna.yourtube

import com.staninna.yourtube.data.HistoryEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryHoldTest {

    private fun entry(id: String) = HistoryEntry(id, watchedAt = 0)

    @Test fun nothingIsHeldAtFirstOrWhenNothingPlays() {
        val hold = HistoryHold()
        assertTrue(hold.records("V"))
        hold.hold(null)
        assertTrue(hold.records("V"))
    }

    @Test fun theHeldVideoIsNotRecordedWhileItShows() {
        val hold = HistoryHold().apply { hold("V") }
        assertFalse(hold.records("V"))
        assertTrue("other videos are", hold.records("W"))
        hold.onPage("V")
        assertFalse("the same video's page keeps it", hold.records("V"))
    }

    @Test fun anotherPageEndsTheHold() {
        val hold = HistoryHold().apply { hold("V") }
        hold.onPage(null)
        assertTrue(hold.records("V"))
    }

    @Test fun openingTheVideoAgainEndsTheHoldButOpeningSomethingElseDoesNot() {
        val hold = HistoryHold().apply { hold("V") }
        hold.onOpen("W")
        hold.onOpen(null)
        assertFalse(hold.records("V"))
        hold.onOpen("V")
        assertTrue(hold.records("V"))
    }

    @Test fun onlyAnUndoThatBringsTheVideoBackEndsTheHold() {
        val hold = HistoryHold().apply { hold("V") }
        hold.onRestore(listOf(entry("X"), entry("Y")))
        assertFalse("an unrelated Undo keeps it out", hold.records("V"))
        hold.onRestore(listOf(entry("X"), entry("V")))
        assertTrue(hold.records("V"))
    }
}
