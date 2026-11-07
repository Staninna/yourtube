package com.staninna.yourtube.web

import com.staninna.yourtube.YouTube
import com.staninna.yourtube.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The pure policies behind the WebView: navigation, identity strategy and seed, page CSS. */
class WebPolicyTest {

    // --- navigation ---

    @Test fun youtubeConsentAndCaptchaStayInApp() {
        for (url in listOf(
            "https://m.youtube.com/watch?v=x",
            "https://www.youtube.com/@x",
            "https://youtube.com/",
            "https://consent.youtube.com/m?continue=x",
            "https://www.google.com/sorry/index?continue=x",
        )) assertTrue(url, NavigationPolicy.opensInApp(url))
    }

    @Test fun everythingElseOpensExternally() {
        for (url in listOf(
            "https://www.google.com/search?q=x",
            "https://accounts.google.com/ServiceLogin",
            "https://music.youtube.com/",
            "http://m.youtube.com/",
            "https://example.com/",
            "intent://watch#Intent;scheme=vnd.youtube;end",
            "mailto:someone@example.com",
            "::not a url",
        )) assertFalse(url, NavigationPolicy.opensInApp(url))
    }

    // --- identity ---

    @Test fun strategyFollowsFeatureSupport() {
        assertTrue(IdentityStrategy.choose(multiProfile = true) is IdentityStrategy.Profiles)
        assertTrue(IdentityStrategy.choose(multiProfile = false) is IdentityStrategy.RestartProcess)
    }

    @Test fun seedRejectsConsentAndMatchesThemeAndLocale() {
        val cookies = IdentitySeed(dark = true, locale = Locale.forLanguageTag("nl-NL")).cookies()
        assertEquals(2, cookies.size)
        assertTrue(cookies[0].startsWith("SOCS=${YouTube.SOCS_REJECT_ALL}; Domain=.youtube.com; Path=/; Secure"))
        assertTrue(cookies[1].startsWith("PREF=f6=400&hl=nl&gl=NL; Domain=.youtube.com"))
    }

    @Test fun lightThemeAndLanguageOnlyLocale() =
        assertEquals("f6=80000&hl=en", IdentitySeed(dark = false, locale = Locale.forLanguageTag("en")).pref())

    // --- page CSS ---

    @Test fun shortsAreHiddenOnlyWhenAsked() {
        assertFalse(PageScripts.css(Settings(hideShorts = false)).contains("ytm-reel-shelf-renderer"))
        assertTrue(PageScripts.css(Settings(hideShorts = true)).contains("ytm-reel-shelf-renderer"))
        assertTrue(PageScripts.css(Settings()).contains("ytm-pivot-bar-renderer{display:none !important}"))
    }
}
