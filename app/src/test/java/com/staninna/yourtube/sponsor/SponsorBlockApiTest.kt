package com.staninna.yourtube.sponsor

import com.staninna.yourtube.data.SegmentCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/** The hash-prefix lookup and its parser, against a real answer and hostile ones. */
class SponsorBlockApiTest {

    private fun fixture(name: String) = checkNotNull(javaClass.classLoader?.getResource("sponsor/$name")).readText()

    private val video = "KfODG5REyss"

    @Test fun hashPrefixIsTheFirstFourHexDigitsOfSha256() {
        // Checked against `printf %s <id> | sha256sum`.
        assertEquals("6973", SponsorBlockApi.hashPrefix("KfODG5REyss"))
        assertEquals("6777", SponsorBlockApi.hashPrefix("eyZEqSPeMwg"))
        assertEquals(4, SponsorBlockApi.hashPrefix("dQw4w9WgXcQ").length)
    }

    @Test fun theUrlNamesThePrefixAndEveryCategoryButNeverTheVideo() {
        val url = SponsorBlockApi.url("https://sponsor.ajay.app", video)
        assertTrue(url, url.startsWith("https://sponsor.ajay.app/api/skipSegments/6973?categories="))
        assertTrue(video !in url)
        val categories = URLDecoder.decode(url.substringAfter("categories="), "UTF-8")
        assertEquals(SegmentCategory.entries.joinToString(",", "[", "]") { "\"${it.apiName}\"" }, categories)
    }

    @Test fun keepsOnlyThisVideosSegmentsInOrder() {
        val segments = SponsorBlockApi.parse(fixture("skip-segments-6973.json"), video)!!
        assertEquals(
            listOf(
                Segment(SegmentCategory.SPONSOR, 1591.369, 1716.662),
                Segment(SegmentCategory.OUTRO, 3890.105, 3894.196),
                Segment(SegmentCategory.OUTRO, 3947.7, 3955.061),
            ),
            segments,
        )
    }

    @Test fun aVideoNotInTheAnswerHasNoSegments() =
        assertEquals(emptyList<Segment>(), SponsorBlockApi.parse(fixture("skip-segments-6973.json"), "aaaaaaaaaaa"))

    @Test fun segmentsTheAppCannotActOnAreDropped() {
        val body = """[{"videoID":"v","segments":[
            {"category":"sponsor","actionType":"skip","segment":[10,20]},
            {"category":"sponsor","actionType":"mute","segment":[30,40]},
            {"category":"exclusive_access","actionType":"full","segment":[0,0]},
            {"category":"poi_highlight","actionType":"poi","segment":[50,50]},
            {"category":"chapter","actionType":"chapter","segment":[1,2]},
            {"category":"sponsor","actionType":"full","segment":[0,100]},
            {"category":"intro","segment":[20,10]},
            {"category":"intro","segment":[-5,3]},
            {"category":"intro","segment":[1,2,3]},
            {"category":"filler","segment":[5,6]},
            {"category":"sponsor","actionType":"skip","segment":[10,20]}
        ]}]"""
        assertEquals(
            listOf(
                Segment(SegmentCategory.FILLER, 5.0, 6.0),
                Segment(SegmentCategory.SPONSOR, 10.0, 20.0),
                Segment(SegmentCategory.SPONSOR, 30.0, 40.0),
            ),
            SponsorBlockApi.parse(body, "v"),
        )
    }

    @Test fun hostileAnswersAreRefusedNotThrown() {
        for (body in listOf(
            "",
            "not json",
            "{}",
            """{"videoID":"v"}""",
            """[{"segments":[]}]""",
            """[{"videoID":"v","segments":[{"category":"sponsor","segment":["a","b"]}]}]""",
            """[{"videoID":"v","segments":[{"category":"sponsor","segment":[1e999,2]}]}]""",
            """[{"videoID":"v","segments":[{"category":"sponsor","segment":[NaN,2]}]}]""",
            """[{"videoID":"v","segments":{"category":"sponsor"}}]""",
            """[{"videoID":"v","segments":[{"category":"sponsor","segment":[1,2]}]}""", // cut short
        )) assertNull(body, SponsorBlockApi.parse(body, "v"))
    }

    @Test fun deepNestingCannotOverflowTheStack() {
        val deep = "[".repeat(200_000) + "]".repeat(200_000)
        assertNull(SponsorBlockApi.parse("""[{"videoID":"v","segments":[{"category":"sponsor","segment":$deep}]}]""", "v"))
        // Nested deep inside a field the parser skips.
        val skipped = SponsorBlockApi.parse("""[{"videoID":"v","junk":$deep,"segments":[{"category":"sponsor","segment":[1,2]}]}]""", "v")
        assertEquals(listOf(Segment(SegmentCategory.SPONSOR, 1.0, 2.0)), skipped)
    }

    @Test fun aFloodOfSegmentsIsCapped() {
        val many = (0 until 1000).joinToString(",") { """{"category":"sponsor","segment":[$it,${it + 0.5}]}""" }
        assertEquals(100, SponsorBlockApi.parse("""[{"videoID":"v","segments":[$many]}]""", "v")!!.size)
    }
}
