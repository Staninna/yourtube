package com.staninna.yourtube.feed

import com.staninna.yourtube.data.FeedVideo
import com.staninna.yourtube.data.VideoLength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which feed videos want their length read, and how answers land. */
class FeedLengthsTest {

    private val now = 1_800_000_000_000L
    private val hold = FeedLengths.RECHECK_MS

    private fun video(id: String, length: VideoLength? = null, short: Boolean = false) =
        FeedVideo(id, "T", "A", "UC", 0, isShort = short, length = length)

    @Test fun lengthsCarryOverFromTheLastList() {
        val known = listOf(video("a", VideoLength.Known(60)), video("b", VideoLength.Live(now)), video("gone", VideoLength.Known(5)))
        val carried = FeedLengths.carry(listOf(video("a"), video("b"), video("new")), known)
        assertEquals(listOf(VideoLength.Known(60), VideoLength.Live(now), null), carried.map { it.length })
    }

    @Test fun aLengthIsForGoodWhileLiveAndUnlistedAnswersHoldForAWhile() {
        assertTrue(FeedLengths.wanted(video("a"), now))
        assertFalse(FeedLengths.wanted(video("a", VideoLength.Known(60)), now + 100 * hold))
        assertFalse("Shorts are on neither page", FeedLengths.wanted(video("s", short = true), now))
        listOf(VideoLength.Live(now), VideoLength.Unlisted(now)).forEach {
            assertFalse(FeedLengths.wanted(video("a", it), now + hold - 1))
            assertTrue(FeedLengths.wanted(video("a", it), now + hold))
        }
    }

    @Test fun answersLandOnlyWhereWantedAndWhatIsLeftIsUnlisted() {
        val videos = listOf(video("a"), video("b", VideoLength.Known(10)), video("c"), video("s", short = true))
        val found = mapOf("a" to VideoLength.Known(60), "b" to VideoLength.Known(99), "s" to VideoLength.Known(30))
        val filled = FeedLengths.fill(videos, found, now)
        assertEquals(listOf(VideoLength.Known(60), VideoLength.Known(10), null, null), filled.map { it.length })
        assertEquals(
            listOf(VideoLength.Known(60), VideoLength.Known(10), VideoLength.Unlisted(now), null),
            FeedLengths.unlisted(filled, now).map { it.length },
        )
    }

    @Test fun streamsAloneSkipTheVideosPage() {
        val stale = VideoLength.Live(now - hold)
        val videos = ChannelPage.Tab.VIDEOS
        val live = ChannelPage.Tab.LIVE
        assertFalse(FeedLengths.worthReading(videos, listOf(video("a", stale)), now))
        assertTrue(FeedLengths.worthReading(live, listOf(video("a", stale)), now))
        assertTrue(FeedLengths.worthReading(videos, listOf(video("a", stale), video("b")), now))
        assertFalse("nothing wanted", FeedLengths.worthReading(live, listOf(video("a", VideoLength.Known(1))), now))
    }
}
