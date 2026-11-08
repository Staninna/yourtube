package com.staninna.yourtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which screen each tab shows, and where Back goes from it. */
class ScreenTest {

    @Test fun watchShowsItsHomeOrItsPage() {
        assertEquals(Screen.HOME, Screen.of(Tab.WATCH, home = true))
        assertEquals(Screen.PAGE, Screen.of(Tab.WATCH, home = false))
        assertEquals(Screen.FEEDS, Screen.of(Tab.FEEDS, home = true))
        assertEquals(Screen.HISTORY, Screen.of(Tab.HISTORY, home = false))
    }

    @Test fun thePageGoesBackThroughItsHistoryThenHome() {
        assertEquals(BackStep.PAGE_HISTORY, Screen.PAGE.back(canGoBack = true, pageLoaded = true))
        assertEquals(BackStep.HOME, Screen.PAGE.back(canGoBack = false, pageLoaded = true))
        assertEquals("a page still loading counts too", BackStep.HOME, Screen.PAGE.back(canGoBack = false, pageLoaded = false))
    }

    @Test fun aLinksFirstPageLeavesTheAppSoOneBackReturnsToTheCaller() {
        assertNull(Screen.PAGE.back(canGoBack = false, pageLoaded = true, fromLink = true))
        assertEquals("pages opened from it still go back first", BackStep.PAGE_HISTORY, Screen.PAGE.back(canGoBack = true, pageLoaded = true, fromLink = true))
    }

    @Test fun theHomeIsTheRoot() {
        assertNull(Screen.HOME.back(canGoBack = true, pageLoaded = true))
    }

    @Test fun nativeTabsGoToWatchOnceItLoadedAPage() {
        listOf(Screen.FEEDS, Screen.LIBRARY, Screen.HISTORY).forEach {
            assertEquals(BackStep.WATCH, it.back(canGoBack = false, pageLoaded = true))
            assertNull("a cold start's tab is the root", it.back(canGoBack = false, pageLoaded = false))
        }
    }
}
