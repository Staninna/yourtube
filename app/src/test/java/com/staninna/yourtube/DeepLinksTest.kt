package com.staninna.yourtube

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinksTest {

    @Test fun youtuBeLinksOpenTheWatchPageWithoutTheShareTracker() {
        assertEquals("https://m.youtube.com/watch?v=dQw4w9WgXcQ", DeepLinks.extract("https://youtu.be/dQw4w9WgXcQ?si=AbCdEfGhIjKlMnOp"))
        assertEquals("https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=42", DeepLinks.extract("https://youtu.be/dQw4w9WgXcQ?si=x&t=42"))
    }

    @Test fun youtubePagesOpenOnTheMobileHost() {
        for ((link, page) in listOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&feature=share&list=PLx1&index=3" to
                "https://m.youtube.com/watch?v=dQw4w9WgXcQ&list=PLx1&index=3",
            "http://youtube.com/watch?v=dQw4w9WgXcQ" to "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://m.youtube.com/shorts/rL0qEmTRI-I?si=x" to "https://m.youtube.com/shorts/rL0qEmTRI-I",
            "https://www.youtube.com/@RickAstleyYT" to "https://m.youtube.com/@RickAstleyYT",
            "https://www.youtube.com/@fern-tv/videos" to "https://m.youtube.com/@fern-tv/videos",
            "https://www.youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw" to "https://m.youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw",
            "https://www.youtube.com/playlist?list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI" to
                "https://m.youtube.com/playlist?list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI",
        )) assertEquals(link, page, DeepLinks.extract(link))
    }

    @Test fun sharedTextYieldsItsFirstYouTubeLink() {
        val text = "Look at this (https://example.com/x) and https://youtu.be/dQw4w9WgXcQ. Then https://youtu.be/rL0qEmTRI-I"
        assertEquals("https://m.youtube.com/watch?v=dQw4w9WgXcQ", DeepLinks.extract(text))
        assertEquals(
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
            DeepLinks.extract("Never Gonna Give You Up\n(https://www.youtube.com/watch?v=dQw4w9WgXcQ)"),
        )
    }

    @Test fun anythingElseOpensNothing() {
        for (text in listOf(
            "no link here",
            "https://example.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com/watch",
            "https://www.youtube.com/watch?v=short",
            "https://youtu.be/",
            "https://youtu.be/dQw4w9WgXcQ/extra",
            "https://www.youtube.com/feed/subscriptions",
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com/channel/notachannel",
            "ftp://youtu.be/dQw4w9WgXcQ",
        )) assertNull(text, DeepLinks.extract(text))
    }

    @Test fun hostileOrOddLinksAreRefusedOrCleaned() {
        assertNull(DeepLinks.extract("https://m.youtube.com.evil.com/watch?v=dQw4w9WgXcQ"))
        assertNull(DeepLinks.extract("https://evil@m.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(DeepLinks.extract("https://m.youtube.com:8443/watch?v=dQw4w9WgXcQ"))
        assertNull(DeepLinks.extract("https://www.youtube.com/@x/../.."))
        assertNull(DeepLinks.extract("https://www.youtube.com/@x/../../feed/history"))
        assertEquals("https://m.youtube.com/watch?v=dQw4w9WgXcQ", DeepLinks.extract("HTTPS://M.YOUTUBE.COM/watch?v=dQw4w9WgXcQ"))
        assertEquals("https://m.youtube.com/watch?v=dQw4w9WgXcQ", DeepLinks.extract("http://m.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(
            "a value is escaped again",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=1m%261",
            DeepLinks.extract("https://youtu.be/dQw4w9WgXcQ?t=1m%261"),
        )
    }

    // --- intents ---

    private val link = "https://youtu.be/dQw4w9WgXcQ"
    private val page = "https://m.youtube.com/watch?v=dQw4w9WgXcQ"

    @Test fun aViewIntentOpensItsLinkAndASendIntentItsText() {
        assertEquals(page, DeepLinks.fromIntent(Intent.ACTION_VIEW, 0, link, text = null))
        assertEquals(page, DeepLinks.fromIntent(Intent.ACTION_SEND, 0, data = null, text = "Look: $link"))
    }

    @Test fun eachActionReadsOnlyItsOwnField() {
        assertNull("VIEW ignores text", DeepLinks.fromIntent(Intent.ACTION_VIEW, 0, data = null, text = link))
        assertNull("SEND ignores data", DeepLinks.fromIntent(Intent.ACTION_SEND, 0, data = link, text = null))
        assertNull("the launcher opens nothing", DeepLinks.fromIntent(Intent.ACTION_MAIN, 0, link, link))
        assertNull(DeepLinks.fromIntent(null, 0, link, link))
    }

    @Test fun anIntentReplayedFromRecentsOpensNothing() {
        val flags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY or Intent.FLAG_ACTIVITY_NEW_TASK
        assertNull(DeepLinks.fromIntent(Intent.ACTION_VIEW, flags, link, null))
        assertEquals(page, DeepLinks.fromIntent(Intent.ACTION_VIEW, Intent.FLAG_ACTIVITY_NEW_TASK, link, null))
    }
}
