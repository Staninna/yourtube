package com.staninna.yourtube.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.OffsetDateTime

/**
 * The RSS parser against a real feed (trimmed to three entries) and the bodies the feed server sends
 * when it fails. Robolectric, because the parser is `android.util.Xml`.
 */
@RunWith(RobolectricTestRunner::class)
class RssFeedTest {

    private fun fixture(name: String) = checkNotNull(javaClass.classLoader?.getResource("feed/$name")).readText()

    private val uc = "UCXuqSBlHAE6Xw-yeJA0Tunw"

    @Test fun readsARealFeedInOrderWithExactTimes() {
        val videos = RssFeed.parse(fixture("rss-ltt.xml"), uc)!!
        assertEquals(listOf("7hlTu95nOCw", "spXuvLHTIs4", "Ao9IQyaH0fk"), videos.map { it.videoId })
        val first = videos[0]
        assertEquals("I’ve never seen this before (and it’s 20 years old.)", first.title)
        assertEquals("Linus Tech Tips", first.author)
        assertEquals(uc, first.channelId)
        assertEquals(OffsetDateTime.parse("2026-11-07T17:01:40+00:00").toInstant().toEpochMilli(), first.publishedAt)
        assertEquals("“Tesla Hater” Finally Tries Full Self Driving", videos[2].title)
        // The feed's own <title> and <author> sit outside entries and name no video.
        assertTrue(videos.none { it.title == "Linus Tech Tips" })
    }

    @Test fun theFeedServersErrorPageIsNotAFeed() {
        assertNull(RssFeed.parse(fixture("rss-404.html"), uc))
    }

    @Test fun aTruncatedFeedIsNotAFeed() {
        assertNull(RssFeed.parse(fixture("rss-truncated.xml"), uc))
    }

    @Test fun garbageAndOtherXmlAreNotFeeds() {
        assertNull(RssFeed.parse("", uc))
        assertNull(RssFeed.parse("not xml at all", uc))
        assertNull(RssFeed.parse("<rss><channel/></rss>", uc))
    }

    @Test fun entriesFromAnotherChannelAreSkippedAndMissingNamesStayBlank() {
        val xml = """<feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns="http://www.w3.org/2005/Atom">
            <entry><yt:videoId>dQw4w9WgXcQ</yt:videoId><yt:channelId>$uc</yt:channelId><published>2026-01-02T10:00:00Z</published></entry>
            <entry><yt:videoId>aaaaaaaaaaa</yt:videoId><yt:channelId>UCuAXFkgsw1L7xaCfnd5JJOw</yt:channelId><title>Elsewhere</title><published>2026-01-02T10:00:00Z</published></entry>
        </feed>"""
        val videos = RssFeed.parse(xml, uc)!!
        assertEquals(listOf("dQw4w9WgXcQ"), videos.map { it.videoId })
        assertEquals("" to "", videos.single().title to videos.single().author)
    }

    @Test fun aChannelWithoutUploadsIsAnEmptyFeed() {
        assertEquals(emptyList<Any>(), RssFeed.parse("""<feed xmlns="http://www.w3.org/2005/Atom"><title>New</title></feed>""", uc))
    }

    @Test fun entriesWithoutAValidIdOrTimeAreSkipped() {
        val xml = """<feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns="http://www.w3.org/2005/Atom">
            <entry><yt:videoId>dQw4w9WgXcQ</yt:videoId><yt:channelId>$uc</yt:channelId><title>Kept</title><published>2026-01-02T10:00:00Z</published></entry>
            <entry><yt:channelId>$uc</yt:channelId><title>No id</title><published>2026-01-02T10:00:00+00:00</published></entry>
            <entry><yt:videoId>aaaaaaaaaaa</yt:videoId><yt:channelId>$uc</yt:channelId><title>No time</title></entry>
            <entry><yt:videoId>bbbbbbbbbbb</yt:videoId><yt:channelId>$uc</yt:channelId><title>Bad time</title><published>yesterday</published></entry>
            <entry><yt:videoId>short</yt:videoId><yt:channelId>$uc</yt:channelId><title>Bad id</title><published>2026-01-02T10:00:00Z</published></entry>
        </feed>"""
        assertEquals(listOf("Kept"), RssFeed.parse(xml, uc)!!.map { it.title })
    }
}
