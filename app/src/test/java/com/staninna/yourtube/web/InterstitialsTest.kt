package com.staninna.yourtube.web

import com.staninna.yourtube.YouTube
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InterstitialsTest {

    private val watch = "https://m.youtube.com/watch?v=dQw4w9WgXcQ"

    @Test fun consentAndCaptchaPagesAreKnownByTheirAddress() {
        assertEquals(Interstitial.CONSENT, Interstitials.of("https://consent.youtube.com/m?continue=https%3A%2F%2Fm.youtube.com%2F&gl=NL"))
        assertEquals(Interstitial.BOT_CHECK, Interstitials.of("https://www.google.com/sorry/index?continue=x&q=y"))
        for (url in listOf(watch, "https://www.google.com/search?q=sorry", "http://consent.youtube.com/m", "::", null)) {
            assertNull(url, Interstitials.of(url))
        }
    }

    private fun report(status: String?, reason: String?, videoId: String? = null, url: String = watch) =
        PageReport(url, PageReport.Reason.LOAD, playability = Playability(status, reason, videoId))

    @Test fun aRefusedPlayerAskingAboutBotsIsABotCheck() {
        assertTrue(Interstitials.isBotCheck(report("LOGIN_REQUIRED", "Sign in to confirm you’re not a bot")))
        assertTrue(Interstitials.isBotCheck(report("LOGIN_REQUIRED", "Sign in to confirm you're not a bot", "dQw4w9WgXcQ")))
    }

    @Test fun otherRefusalsAndOtherVideosAreNot() {
        assertFalse(Interstitials.isBotCheck(report("OK", null)))
        assertFalse("age gate", Interstitials.isBotCheck(report("LOGIN_REQUIRED", "Sign in to confirm your age")))
        assertFalse(Interstitials.isBotCheck(report("UNPLAYABLE", "Video unavailable bot")))
        assertFalse("another video's response", Interstitials.isBotCheck(report("LOGIN_REQUIRED", "not a bot", "rL0qEmTRI-I")))
        assertFalse(Interstitials.isBotCheck(PageReport(watch, PageReport.Reason.LOAD)))
        assertFalse("a word containing bot", Interstitials.isBotCheck(report("LOGIN_REQUIRED", "Sign in to use the robot")))
    }

    @Test fun aNewIdentityGoesWhereTheInterstitialWasHeaded() {
        assertEquals(watch, Interstitials.reloadUrl(watch))
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            Interstitials.reloadUrl("https://www.google.com/sorry/index?continue=https://www.youtube.com/watch%3Fv%3DdQw4w9WgXcQ&q=EgQ"),
        )
        assertEquals("https://m.youtube.com/@x?cbrd=1", Interstitials.reloadUrl("https://consent.youtube.com/m?continue=https%3A%2F%2Fm.youtube.com%2F%40x%3Fcbrd%3D1&gl=NL"))
        assertEquals(YouTube.HOME, Interstitials.reloadUrl("https://www.google.com/sorry/index"))
        assertEquals(YouTube.HOME, Interstitials.reloadUrl("https://www.google.com/sorry/index?continue=https://evil.example/"))
    }
}
