package com.staninna.yourtube.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemePrefTest {

    @Test fun anEmptyPrefGetsJustTheThemeFlag() {
        assertEquals("f6=400", ThemePref.withTheme(null, dark = true))
        assertEquals("f6=80000", ThemePref.withTheme("", dark = false))
    }

    @Test fun switchingThemeKeepsYouTubesOtherBitsAndKeys() {
        // What YouTube had written after a light session (measured).
        val light = "f6=40080000&hl=en&gl=US&tz=Europe.Amsterdam"
        val dark = ThemePref.withTheme(light, dark = true)
        assertEquals("f6=40000400&hl=en&gl=US&tz=Europe.Amsterdam", dark)
        assertEquals(light, ThemePref.withTheme(dark, dark = false))
        assertEquals(dark, ThemePref.withTheme(dark, dark = true))
    }

    @Test fun aMissingF6IsAddedFirst() =
        assertEquals("f6=400&hl=nl", ThemePref.withTheme("hl=nl", dark = true))

    @Test fun anUnreadableF6StartsOver() =
        assertEquals("f6=80000&hl=en", ThemePref.withTheme("f6=zz&hl=en", dark = false))

    @Test fun prefIsReadFromACookieHeader() {
        assertEquals("f6=400&hl=en", ThemePref.read("SOCS=CAI; PREF=f6=400&hl=en; VISITOR_INFO1_LIVE=x"))
        assertNull(ThemePref.read("SOCS=CAI"))
        assertNull(ThemePref.read(null))
    }

    @Test fun anUnchangedThemeNeedsNothing() {
        assertEquals(ThemeSync.NONE, ThemeSync.decide(syncedDark = true, dark = true, pageLoaded = true, videoStarted = false))
        assertEquals(ThemeSync.NONE, ThemeSync.decide(syncedDark = false, dark = false, pageLoaded = false, videoStarted = true))
    }

    @Test fun aLoadedIdlePageReloads() =
        assertEquals(ThemeSync.REWRITE_AND_RELOAD, ThemeSync.decide(syncedDark = false, dark = true, pageLoaded = true, videoStarted = false))

    /** A started video, playing or paused, is never reloaded: the reload would play it again. */
    @Test fun aStartedVideoOrAnUnloadedPageOnlyGetsTheCookie() {
        assertEquals(ThemeSync.REWRITE, ThemeSync.decide(syncedDark = false, dark = true, pageLoaded = true, videoStarted = true))
        assertEquals(ThemeSync.REWRITE, ThemeSync.decide(syncedDark = true, dark = false, pageLoaded = false, videoStarted = false))
    }
}
