package com.staninna.yourtube.feed

import com.staninna.yourtube.data.VideoLength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The videos-page fallback against a real mobile page (trimmed to three videos) and broken ones. */
class ChannelPageTest {

    private val uc = "UCSJ4gkVC6NrvII8umztf0Ow"
    private val now = 1_800_000_000_000L
    private val day = 24 * 60 * 60 * 1000L
    private val fixture = checkNotNull(javaClass.classLoader?.getResource("feed/videos-page.html")).readText()

    /** A page as YouTube writes it: [json] inside a single-quoted literal with its escapes. */
    private fun page(json: String): String {
        val literal = json.replace("\\", "\\\\").replace("/", "\\/")
            .replace("\"", "\\x22").replace("{", "\\x7b").replace("}", "\\x7d").replace("'", "\\x27")
        return "<html><script>var ytInitialData = '$literal';</script></html>"
    }

    private fun grid(vararg videos: String, channel: String = uc) =
        """{"contents":{"items":[${videos.joinToString(",")}]},"metadata":{"channelMetadataRenderer":{"title":"Lofi Girl","externalId":"$channel"}}}"""

    private fun video(id: String, age: String?, title: String = "A video") =
        """{"compactVideoRenderer":{"videoId":"$id","title":{"runs":[{"text":"$title"}]}""" +
            (age?.let { ""","publishedTimeText":{"simpleText":"$it"}""" } ?: "") + "}}"

    @Test fun readsARealPageNewestFirstWithRoundedTimes() {
        val videos = ChannelPage.parse(fixture, uc, now)!!
        assertEquals(listOf("d3QQHqRmvH4", "PVSOJyG5WKU", "vadHXNBy4jQ"), videos.map { it.videoId })
        assertEquals(listOf(now - 4 * day, now - 14 * day - 1, now - 21 * day - 2), videos.map { it.publishedAt })
        assertEquals("8h of halloween lofi 🎃 spooky ambience to get chills to", videos[2].title)
        assertEquals(setOf("Lofi Girl"), videos.map { it.author }.toSet())
        assertEquals(setOf(uc), videos.map { it.channelId }.toSet())
    }

    @Test fun videosOfTheSameAgeKeepThePagesOrder() {
        val html = page(grid(video("aaaaaaaaaaa", "1 month ago"), video("bbbbbbbbbbb", "1 month ago")))
        val videos = ChannelPage.parse(html, uc, now)!!
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), videos.sortedByDescending { it.publishedAt }.map { it.videoId })
    }

    @Test fun anotherChannelsPageIsNotThisChannel() {
        assertNull(ChannelPage.parse(fixture, "UCuAXFkgsw1L7xaCfnd5JJOw", now))
    }

    @Test fun pagesWithoutReadableDataYieldNothing() {
        // The consent redirect's page, a cut-off literal, and a literal that is not JSON.
        assertNull(ChannelPage.parse("<html><form action=\"https://consent.youtube.com/save\"></form></html>", uc, now))
        assertNull(ChannelPage.parse(fixture.substring(0, fixture.length / 2), uc, now))
        assertNull(ChannelPage.parse("<script>var ytInitialData = 'not json';</script>", uc, now))
        assertNull(ChannelPage.parse("<script>var ytInitialData = '\\x7b\\x';</script>", uc, now))
        assertNull(ChannelPage.parse(page("""{"metadata":{}}"""), uc, now))
    }

    @Test fun aChannelWithNoUploadsIsEmpty() {
        assertEquals(emptyList<Any>(), ChannelPage.parse(page(grid()), uc, now))
    }

    @Test fun videosWithoutAnAgeAreSkippedButNoAgesAtAllIsDrift() {
        val some = ChannelPage.parse(page(grid(video("aaaaaaaaaaa", null), video("bbbbbbbbbbb", "2 hours ago"))), uc, now)!!
        assertEquals(listOf("bbbbbbbbbbb"), some.map { it.videoId })
        assertNull("a grid of videos without one readable age is markup drift", ChannelPage.parse(page(grid(video("aaaaaaaaaaa", "vor 2 Tagen"))), uc, now))
    }

    @Test fun malformedVideoIdsAreSkipped() {
        val videos = ChannelPage.parse(page(grid(video("nope", "1 day ago"), video("bbbbbbbbbbb", "1 day ago"))), uc, now)!!
        assertEquals(listOf("bbbbbbbbbbb"), videos.map { it.videoId })
    }

    @Test fun titlesKeepQuotesBackslashesAndAccents() {
        val html = page(grid(video("aaaaaaaaaaa", "1 day ago", title = """Say \"hi\" to C:\\temp café""")))
        assertEquals("""Say "hi" to C:\temp café""", ChannelPage.parse(html, uc, now)!!.single().title)
    }

    @Test fun aHostilelyDeepTreeDoesNotOverflowTheStack() {
        val depth = 100_000
        val json = """{"contents":""" + "[".repeat(depth) + "]".repeat(depth) +
            ""","metadata":{"channelMetadataRenderer":{"title":"Lofi Girl","externalId":"$uc"}}}"""
        assertNull("refused before the JSON reader recurses into it", ChannelPage.parse(page(json), uc, now))
        assertEquals(3, ChannelPage.nestingDepth("""{"a":[{"b":"[[[{{"}]}"""))
        assertEquals("the real page is far below the cap", true, ChannelPage.nestingDepth(ChannelPage.jsStringLiteral(fixture, fixture.indexOf("= '") + 3)!!) < 40)
    }

    @Test fun jsStringLiteralDecodesYouTubesEscapes() {
        val source = """x = '\x7b\x22a\x22:\x22\\u00e9\/\u00e9\n\x27\x22\x7d'; rest"""
        assertEquals("{\"a\":\"\\u00e9/é\n'\"}", ChannelPage.jsStringLiteral(source, source.indexOf('\'') + 1))
        assertNull("an unterminated literal", ChannelPage.jsStringLiteral("'abc", 1))
    }

    @Test fun aRealVideosPageGivesViewsAndLengths() {
        val videos = ChannelPage.parse(fixture, uc, now)!!
        assertEquals(listOf(109_308L, 243_508L, 569_297L), videos.map { it.views })
        assertEquals(listOf(73L, 39L, 28_800L).map(VideoLength::Known), videos.map { it.length })
    }

    @Test fun aRealLivePageMarksStreamsOnAirAndKeepsLengthsOfPastOnes() {
        val streams = checkNotNull(javaClass.classLoader?.getResource("feed/streams-page.html")).readText()
        assertEquals(
            mapOf(
                "5qap5aO4i9A" to VideoLength.Known(20_843L * 3600 + 43 * 60 + 51),
                "4xDzrJKXOOY" to VideoLength.Live(now),
                "0sfDth4lMLc" to VideoLength.Live(now),
            ),
            ChannelPage.lengths(streams, uc, now),
        )
        assertNull("another channel's page", ChannelPage.lengths(streams, "UCuAXFkgsw1L7xaCfnd5JJOw", now))
        assertEquals("the videos page has a length for each", 3, ChannelPage.lengths(fixture, uc, now)!!.size)
    }

    @Test fun lengthsReadAsYouTubeWritesThem() {
        assertEquals(39L, ChannelPage.parseLength("0:39"))
        assertEquals(245L, ChannelPage.parseLength("4:05"))
        assertEquals(3729L, ChannelPage.parseLength("1:02:09"))
        assertEquals(75_037_431L, ChannelPage.parseLength("20,843:43:51"))
        listOf("", "LIVE", "12", "1:2:3:4", "1:60", "0:00", "-1:00", "1:-5").forEach { assertNull(it, ChannelPage.parseLength(it)) }
    }

    @Test fun viewCountsReadAsYouTubeWritesThem() {
        assertEquals(1_742_245L, ChannelPage.parseViews("1,742,245 views"))
        assertEquals(1L, ChannelPage.parseViews("1 view"))
        assertEquals(0L, ChannelPage.parseViews("No views"))
        assertNull("viewers of a stream are not views", ChannelPage.parseViews("1,071 watching"))
        assertNull(ChannelPage.parseViews("1.7M views"))
    }
}
