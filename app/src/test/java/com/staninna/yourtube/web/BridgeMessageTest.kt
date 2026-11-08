package com.staninna.yourtube.web

import com.staninna.yourtube.data.PlaybackKind
import com.staninna.yourtube.data.SegmentAction
import com.staninna.yourtube.data.SegmentCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BridgeMessageTest {

    @Test fun decodesASwipe() {
        assertEquals(
            Swipe(Swipe.Control.VOLUME, Swipe.Phase.MOVE, 0.25),
            BridgeMessage.decode("""{"type":"swipe","control":"volume","phase":"move","amount":0.25}"""),
        )
        assertEquals(
            Swipe(Swipe.Control.BRIGHTNESS, Swipe.Phase.END),
            BridgeMessage.decode("""{"type":"swipe","control":"brightness","phase":"end"}"""),
        )
    }

    @Test fun aSwipeOfAnUnknownControlIsDropped() =
        assertNull(BridgeMessage.decode("""{"type":"swipe","control":"zoom","phase":"move","amount":1}"""))

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

    @Test fun playbackCarriesThePagesPausedFlag() {
        val seekWhilePaused = """{"type":"playback","kind":"time","videoId":"v","paused":true,"position":40,"duration":300}"""
        assertEquals(PlaybackEvent(PlaybackKind.TIME, "v", 40.0, 300.0, paused = true), BridgeMessage.decode(seekWhilePaused))
    }

    @Test fun playbackCarriesTheRate() {
        val faster = """{"type":"playback","kind":"time","videoId":"v","paused":false,"rate":1.5,"position":40,"duration":300}"""
        assertEquals(1.5, (BridgeMessage.decode(faster) as PlaybackEvent).rate, 0.0)
        val older = """{"type":"playback","kind":"time","videoId":"v","position":40,"duration":300}"""
        assertEquals("a page script without the field plays at normal speed", 1.0, (BridgeMessage.decode(older) as PlaybackEvent).rate, 0.0)
    }

    @Test fun playbackWithoutPausedReadsItFromTheKind() {
        val decode = { kind: String -> (BridgeMessage.decode("""{"type":"playback","kind":"$kind","videoId":"v","position":1,"duration":2}""") as PlaybackEvent).paused }
        assertEquals(listOf(false, true, true, false), listOf("play", "pause", "ended", "time").map(decode))
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
        assertEquals("""{"type":"resume","videoId":"v","position":55.0,"muted":false}""", BridgeMessage.encode(ResumeReply("v", 55.0, muted = false)))
        assertEquals("""{"type":"resume","videoId":"v","position":null,"muted":false}""", BridgeMessage.encode(ResumeReply("v", null, muted = false)))
    }

    // --- content filtering ---

    @Test fun decodesSegmentRequestsSkipsAndAdLeaks() {
        assertEquals(SegmentsRequest("dQw4w9WgXcQ"), BridgeMessage.decode("""{"type":"segments","videoId":"dQw4w9WgXcQ"}"""))
        assertEquals(
            SegmentSkipped("v", SegmentCategory.SELF_PROMO, 1.5, 9.0),
            BridgeMessage.decode("""{"type":"skipped","videoId":"v","category":"selfpromo","start":1.5,"end":9}"""),
        )
        assertEquals(AdLeak("https://m.youtube.com/watch?v=x"), BridgeMessage.decode("""{"type":"adLeak","url":"https://m.youtube.com/watch?v=x"}"""))
        assertNull(BridgeMessage.decode("""{"type":"skipped","videoId":"v","category":"poi_highlight","start":1,"end":2}"""))
    }

    @Test fun encodesSegmentRepliesAndUndo() {
        assertEquals(
            """{"type":"segments","videoId":"v","segments":[{"start":10.0,"end":20.0,"category":"sponsor","action":"skip"}]}""",
            BridgeMessage.encode(SegmentsReply("v", listOf(PageSegment(10.0, 20.0, SegmentCategory.SPONSOR, SegmentAction.SKIP)))),
        )
        assertEquals("""{"type":"undoSkip","videoId":"v","start":10.0}""", BridgeMessage.encode(UndoSkip("v", 10.0)))
    }

    // --- media session ---

    @Test fun encodesPlayerCommands() {
        assertEquals("""{"type":"play"}""", BridgeMessage.encode(PlayerCommand.Play))
        assertEquals("""{"type":"pause"}""", BridgeMessage.encode(PlayerCommand.Pause))
        assertEquals("""{"type":"seek","videoId":"v","position":95.5}""", BridgeMessage.encode(PlayerCommand.Seek("v", 95.5)))
    }

    // --- picture-in-picture ---

    @Test fun decodesAVideoBox() {
        val message = BridgeMessage.decode(
            """{"type":"videoBox","videoWidth":854,"videoHeight":480,"left":0,"top":0,"right":412,"bottom":231.75,"viewportWidth":412}""",
        )
        assertEquals(VideoBox(854, 480, 0.0, 0.0, 412.0, 231.75, 412.0), message)
    }

    @Test fun decodesPlayabilityAndTheConsentMessage() {
        val report = BridgeMessage.decode(
            """{"type":"page","reason":"load","url":"u","playability":{"status":"LOGIN_REQUIRED","reason":"not a bot","videoId":null}}""",
        ) as PageReport
        assertEquals(Playability("LOGIN_REQUIRED", "not a bot"), report.playability)
        assertEquals(ConsentRejected, BridgeMessage.decode("""{"type":"consent"}"""))
    }

    @Test fun aResumeReplyAlwaysSaysMutedAndCarriesPlaybackOnlyAfterANewIdentity() {
        assertEquals("""{"type":"resume","videoId":"v","position":12.0,"muted":true}""", BridgeMessage.encode(ResumeReply("v", 12.0, muted = true)))
        assertEquals(
            """{"type":"resume","videoId":"v","position":null,"muted":false,"carried":{"rate":1.5,"paused":true}}""",
            BridgeMessage.encode(ResumeReply("v", null, muted = false, Carried(1.5, paused = true))),
        )
    }

    @Test fun decodesTheViewersMute() {
        assertEquals(ViewerMute(true), BridgeMessage.decode("""{"type":"mute","muted":true}"""))
        assertNull("muted is required", BridgeMessage.decode("""{"type":"mute"}"""))
    }
}
