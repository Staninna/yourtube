package com.staninna.yourtube.web

import com.staninna.yourtube.data.PlaybackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BridgeMessageTest {

    @Test fun decodesAPageReport() {
        val message = BridgeMessage.decode(
            """{"type":"page","reason":"metadata","url":"https://m.youtube.com/watch?v=dQw4w9WgXcQ",
               "canonical":"https://m.youtube.com/watch?v=dQw4w9WgXcQ","title":"T - YouTube",
               "player":{"videoId":"dQw4w9WgXcQ","channelId":"UCx","title":"T","author":"A","extra":1}}""",
        )
        assertEquals(
            PageReport(
                url = "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
                reason = PageReport.Reason.METADATA,
                canonical = "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
                title = "T - YouTube",
                player = PlayerFacts("dQw4w9WgXcQ", "UCx", "T", "A"),
            ),
            message,
        )
    }

    @Test fun absentPlayerFieldsDecodeAsNull() {
        val message = BridgeMessage.decode(
            """{"type":"page","reason":"metadata","url":"u","player":{"videoId":"v","channelId":null,"title":null,"author":null}}""",
        )
        assertEquals(PlayerFacts(videoId = "v"), (message as PageReport).player)
    }

    @Test fun decodesAPageReportWithoutPlayerOrCanonical() {
        val message = BridgeMessage.decode("""{"type":"page","reason":"load","url":"https://m.youtube.com/","canonical":null,"player":null}""")
        assertEquals(PageReport("https://m.youtube.com/", PageReport.Reason.LOAD), message)
    }

    @Test fun decodesPlaybackEvents() {
        for ((wire, kind) in mapOf(
            "play" to PlaybackKind.PLAY,
            "pause" to PlaybackKind.PAUSE,
            "ended" to PlaybackKind.ENDED,
            "time" to PlaybackKind.TIME,
            "metadata" to PlaybackKind.METADATA,
        )) {
            val message = BridgeMessage.decode("""{"type":"playback","kind":"$wire","videoId":"v","position":12.5,"duration":300}""")
            assertEquals(PlaybackEvent(kind, "v", 12.5, 300.0), message)
        }
    }

    @Test fun decodesAResumeRequest() =
        assertEquals(ResumeRequest("v"), BridgeMessage.decode("""{"type":"resume","videoId":"v"}"""))

    @Test fun dropsWhatDoesNotFitTheSchema() {
        assertNull(BridgeMessage.decode("not json"))
        assertNull(BridgeMessage.decode("""{"type":"unknown"}"""))
        assertNull(BridgeMessage.decode("""{"videoId":"v"}"""))
        assertNull(BridgeMessage.decode("""{"type":"playback","kind":"seek","videoId":"v","position":1,"duration":2}"""))
        assertNull(BridgeMessage.decode("""{"type":"page","reason":"load"}"""))
    }

    @Test fun encodesResumeReplies() {
        assertEquals("""{"type":"resume","videoId":"v","position":55.0}""", BridgeMessage.encode(ResumeReply("v", 55.0)))
        assertEquals("""{"type":"resume","videoId":"v","position":null}""", BridgeMessage.encode(ResumeReply("v", null)))
    }
}
