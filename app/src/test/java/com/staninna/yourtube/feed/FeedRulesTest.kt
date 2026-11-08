package com.staninna.yourtube.feed

import com.staninna.yourtube.data.FeedPageSize
import com.staninna.yourtube.data.FeedVideo
import com.staninna.yourtube.data.ItemType
import com.staninna.yourtube.data.Library
import com.staninna.yourtube.data.StarredItem
import com.staninna.yourtube.data.Tag
import com.staninna.yourtube.data.TagFilter
import com.staninna.yourtube.data.TagMatch
import com.staninna.yourtube.feed.FeedView.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Merge and sort, the tag filter, paging, statuses, fresh videos and the cache, as pure rules. */
class FeedRulesTest {

    private val music = Tag("t-music", "Music", 0)
    private val news = Tag("t-news", "News", 1)

    private fun channel(id: String, vararg tags: Tag, avatar: String? = null) =
        StarredItem(id, ItemType.CHANNEL, "Channel $id", thumbnail = avatar, url = "u", channelId = id, tagIds = tags.map { it.id }.toSet())

    private fun video(id: String, channel: String, at: Long) = FeedVideo(id, "Video $id", "Channel $channel", channel, at)

    private val a = channel("A", music, avatar = "https://a.png")
    private val b = channel("B", news)
    private val c = channel("C")
    private val library = Library(
        items = listOf(a, b, c, StarredItem("v", ItemType.VIDEO, "A saved video", url = "u", channelId = "A")),
        tags = listOf(music, news),
    )

    private fun ok(vararg videos: FeedVideo) = ChannelFetch.Ok(videos.toList(), FeedSource.RSS)

    private val results = FeedResults()
        .finish("A", ok(video("a1", "A", 50), video("a2", "A", 10)))
        .finish("B", ok(video("b1", "B", 40), video("a1", "A", 50)))
        .finish("C", ok(video("c1", "C", 30)))

    private fun view(
        res: FeedResults = results,
        controls: FeedControls = FeedControls(),
        size: FeedPageSize = FeedPageSize.P50,
        lib: Library = library,
        removeShorts: Boolean = false,
    ) = FeedRules.view(lib, res, controls, size, removeShorts)

    @Test fun mergesEveryChannelNewestFirstEachVideoOnce() {
        val v = view()
        assertEquals(listOf("a1", "b1", "c1", "a2"), v.videos.map { it.videoId })
        assertEquals(Status.LIST, v.status)
        assertEquals(3, v.subscriptions)
        assertEquals(mapOf("A" to "https://a.png"), v.avatars)
    }

    @Test fun newestMergesEverySubscriptionAndKeepsTheNewestFew() {
        assertEquals(listOf("a1", "b1"), FeedRules.newest(library, results, removeShorts = false, count = 2).map { it.videoId })
        assertEquals(listOf("a1", "b1", "c1", "a2"), FeedRules.newest(library, results, removeShorts = false, count = 10).map { it.videoId })
        val unsubscribed = library.copy(items = library.items.filterNot { it.id == "A" })
        // B's feed lists a1 too, so it stays; a2 leaves with A.
        assertEquals(listOf("a1", "b1", "c1"), FeedRules.newest(unsubscribed, results, removeShorts = false, count = 10).map { it.videoId })
    }

    @Test fun newestLeavesShortsOutWithRemoveShorts() {
        val res = results.finish("C", ok(video("s1", "C", 60).copy(isShort = true), video("c1", "C", 30)))
        assertEquals("s1", FeedRules.newest(library, res, removeShorts = false, count = 1).single().videoId)
        assertEquals("a1", FeedRules.newest(library, res, removeShorts = true, count = 1).single().videoId)
    }

    @Test fun anUnsubscribedChannelsVideosLeave() {
        val lib = library.copy(items = library.items.filterNot { it.id == "C" })
        assertEquals(listOf("a1", "b1", "a2"), view(lib = lib).videos.map { it.videoId })
        val kept = results.keep(setOf("A", "B"))
        assertEquals(setOf("A", "B"), kept.channels.keys)
    }

    @Test fun pagesRevealPageSizeVideosAtATime() {
        val many = FeedResults().finish("A", ChannelFetch.Ok(List(60) { video("v%010d".format(it), "A", 1_000L - it) }, FeedSource.PAGE))
        val first = view(many, size = FeedPageSize.P25)
        assertEquals(25, first.videos.size)
        assertEquals(60, first.matching)
        assertTrue(first.hasMore)
        val second = view(many, FeedControls(pages = 2), FeedPageSize.P25)
        assertEquals(50, second.videos.size)
        assertEquals("the newest stay first", first.videos, second.videos.take(25))
        val last = view(many, FeedControls(pages = 3), FeedPageSize.P25)
        assertEquals(60, last.videos.size)
        assertFalse(last.hasMore)
    }

    @Test fun theTagFilterMatchesAnyTagByDefault() {
        val v = view(controls = FeedControls(filter = TagFilter(setOf(music.id))))
        assertEquals(listOf(music, news), v.tags.tags)
        assertEquals(mapOf(music.id to 1, news.id to 1), v.tags.counts)
        assertEquals("C carries no tag", 1, v.tags.untagged)
        assertEquals(listOf("a1", "a2"), v.videos.map { it.videoId })
        val both = view(controls = FeedControls(filter = TagFilter(setOf(music.id, news.id))))
        assertEquals(listOf("a1", "b1", "a2"), both.videos.map { it.videoId })
    }

    @Test fun matchingAllTagsCanLeaveNoChannel() {
        val all = view(controls = FeedControls(filter = TagFilter(setOf(music.id, news.id), match = TagMatch.ALL)))
        assertEquals(0, all.channels)
        assertTrue(all.videos.isEmpty())
        assertEquals(Status.NO_MATCH, all.status)

        val both = library.copy(items = library.items.map { if (it.id == "B") it.copy(tagIds = setOf(music.id, news.id)) else it })
        val v = view(lib = both, controls = FeedControls(filter = TagFilter(setOf(music.id, news.id), match = TagMatch.ALL)))
        assertEquals("B also lists a1", listOf("a1", "b1"), v.videos.map { it.videoId })
    }

    @Test fun untaggedShowsOnlyChannelsWithoutTags() {
        val v = view(controls = FeedControls(filter = TagFilter(untagged = true)))
        assertEquals(listOf("c1"), v.videos.map { it.videoId })
        assertEquals(1, v.channels)
    }

    @Test fun aChoiceNoChannelOffersAnyMoreDropsOut() {
        val v = view(controls = FeedControls(filter = TagFilter(setOf("gone"))))
        assertEquals(emptySet<String>(), v.tags.filter.tags)
        assertEquals(4, v.videos.size)
        assertEquals(TagFilter(), FeedRules.inEffect(library, TagFilter(setOf("gone"))))
        val allTagged = library.copy(items = library.items.filterNot { it.id == "C" })
        assertEquals(TagFilter(), FeedRules.inEffect(allTagged, TagFilter(untagged = true)))
    }

    @Test fun aFailedChannelKeepsItsLastVideosAndIsNamed() {
        val failed = results.finish("C", ChannelFetch.Failed(FetchFailure.UNAVAILABLE))
        val v = view(failed)
        assertEquals("the list stays whole", 4, v.videos.size)
        assertEquals(listOf("C"), v.failed.map { it.id })
        assertFalse(v.offline)
        assertEquals(Status.LIST, v.status)
        assertTrue(view(failed.start(setOf("C"))).retrying)
        assertEquals("a success clears it", emptyList<StarredItem>(), view(failed.finish("C", ok())).failed)
    }

    @Test fun failuresBehindUnselectedChipsAreNotShown() {
        val failed = results.finish("C", ChannelFetch.Failed(FetchFailure.UNAVAILABLE))
        assertEquals(emptyList<StarredItem>(), view(failed, FeedControls(filter = TagFilter(setOf(music.id)))).failed)
    }

    @Test fun statusSaysWhyTheListIsEmpty() {
        assertEquals(Status.NO_SUBSCRIPTIONS, view(lib = Library()).status)
        assertEquals("nothing fetched yet counts as loading", Status.LOADING, view(FeedResults()).status)
        assertEquals(Status.LOADING, view(FeedResults().start(setOf("A", "B", "C"))).status)

        val offline = ChannelFetch.Failed(FetchFailure.OFFLINE)
        val allOffline = FeedResults().finish("A", offline).finish("B", offline).finish("C", offline)
        assertEquals(Status.OFFLINE, view(allOffline).status)
        assertTrue(view(allOffline).offline)

        val down = FeedResults().finish("A", ChannelFetch.Failed(FetchFailure.UNAVAILABLE)).finish("B", offline).finish("C", ok())
        assertEquals(Status.FAILED, view(down).status)
        assertFalse("one failure was not about the connection", view(down).offline)

        val empty = FeedResults().finish("A", ok()).finish("B", ok()).finish("C", ok())
        assertEquals(Status.NO_VIDEOS, view(empty).status)

        val onlyC = FeedResults().finish("A", ok()).finish("B", ok()).finish("C", ok(video("c1", "C", 1)))
        assertEquals(Status.NO_MATCH, view(onlyC, FeedControls(filter = TagFilter(setOf(music.id)))).status)
    }

    @Test fun refreshingIsFetchingBehindAListOnScreen() {
        assertFalse("the first load has the loading state instead", view(FeedResults().start(setOf("A", "B", "C"))).refreshing)
        assertTrue(view(results.start(setOf("A"))).refreshing)
        assertFalse(view(results).refreshing)
        val partial = FeedResults().start(setOf("A", "B", "C")).finish("A", ok(video("a1", "A", 5)))
        assertEquals("the first load fills in progressively", Status.LIST, view(partial).status)
        assertTrue(view(partial).refreshing)
    }

    @Test fun freshVideosAreUnseenAndNewerThanTheChannelsLastList() {
        val refreshed = results
            .finish("A", ok(video("n2", "A", 70), video("a1", "A", 50), video("old", "A", 45), video("a2", "A", 10)))
            .finish("B", ok(video("n1", "B", 60), video("b1", "B", 40)))
        assertEquals(setOf("n2", "n1"), refreshed.fresh)
        assertEquals(2, view(refreshed).newVideos)
        assertEquals("the pill counts only what the chips let through", 1, view(refreshed, FeedControls(filter = TagFilter(setOf(music.id)))).newVideos)
    }

    @Test fun aChannelsFirstListIsNotNews() {
        assertEquals(emptySet<String>(), results.fresh)
        val failedFirst = FeedResults().finish("A", ChannelFetch.Failed(FetchFailure.UNAVAILABLE))
        assertNull(failedFirst.channels.getValue("A").videos)
        assertEquals(emptySet<String>(), failedFirst.finish("A", ok(video("a1", "A", 50))).fresh)
    }

    @Test fun anAbandonedFetchLeavesNothingBehind() {
        val r = FeedResults().start(setOf("A")).abandon("A")
        assertEquals(emptySet<String>(), r.loading)
        assertEquals(emptyMap<String, ChannelFeed>(), r.channels)
    }

    @Test fun theCacheRoundTripsIntoAColdStart() {
        val cache = results.finish("C", ChannelFetch.Failed(FetchFailure.OFFLINE)).cache()
        assertEquals(setOf("A", "B", "C"), cache.keys)
        val cold = FeedResults.cached(cache + ("Z" to listOf(video("z1", "Z", 1))), FeedRules.subscriptions(library))
        assertEquals("unsubscribed channels stay out", setOf("A", "B", "C"), cold.channels.keys)
        val v = view(cold)
        assertEquals(listOf("a1", "b1", "c1", "a2"), v.videos.map { it.videoId })
        assertFalse("cached channels are not loading by themselves", v.loading)
    }

    @Test fun aMissingNameOrTitleShowsTheSubscriptionsTitle() {
        val blank = FeedResults().finish("A", ok(FeedVideo("aaaaaaaaaaa", "", "", "A", 1)))
        val row = view(blank).videos.single()
        assertEquals("Channel A", row.author)
        assertEquals("Channel A", row.title)
    }

    @Test fun subscriptionsAreStarredChannelsOnly() {
        assertEquals(setOf("A", "B", "C"), FeedRules.subscriptions(library))
    }

    @Test fun removeShortsLeavesShortsOutOfTheListAndItsCounts() {
        val short = video("s1", "C", 60).copy(isShort = true)
        val res = results.finish("C", ok(short, video("c1", "C", 30)))
        assertEquals(listOf("s1", "a1", "b1", "c1", "a2"), view(res).videos.map { it.videoId })
        val removed = view(res, removeShorts = true)
        assertEquals(listOf("a1", "b1", "c1", "a2"), removed.videos.map { it.videoId })
        assertEquals(4, removed.matching)
    }

    @Test fun aChannelOfOnlyShortsHasNoVideosWhileShortsAreRemoved() {
        val only = Library(items = listOf(c))
        val res = FeedResults().finish("C", ok(video("s1", "C", 60).copy(isShort = true)))
        assertEquals(Status.NO_VIDEOS, view(res, lib = only, removeShorts = true).status)
        assertEquals(Status.LIST, view(res, lib = only).status)
    }

    @Test fun latestUploadsAreEachChannelsNewestVideoShortsAside() {
        val res = FeedResults()
            .finish("A", ok(video("s1", "A", 90).copy(isShort = true), video("a1", "A", 50)))
            .finish("C", ok(video("s2", "C", 60).copy(isShort = true)))
            .finish("B", ChannelFetch.Failed(FetchFailure.OFFLINE))
        assertEquals(mapOf("A" to 90L, "C" to 60L), FeedRules.latestUploads(res, removeShorts = false))
        assertEquals(mapOf("A" to 50L), FeedRules.latestUploads(res, removeShorts = true))
    }

    @Test fun aCacheWrittenByTheBackgroundCheckIsAdoptedUnlessFetching() {
        val start = FeedResults().finish("A", ok(video("a1", "A", 10)))
        val outside = mapOf("A" to listOf(video("a2", "A", 20), video("a1", "A", 10)), "Gone" to listOf(video("g", "Gone", 5)))
        val adopted = start.adopt(outside, setOf("A"), cap = 30)
        assertEquals(listOf("a2", "a1"), adopted.channels.getValue("A").videos!!.map { it.videoId })
        assertEquals("a new upload is fresh, as from a refresh", setOf("a2"), adopted.fresh)
        assertNull("an unsubscribed channel stays out", adopted.channels["Gone"])
        assertEquals("our own cache changes nothing", start, start.adopt(start.cache(), setOf("A"), cap = 30))
        assertEquals("a channel being fetched waits for its own answer", start.start(setOf("A")), start.start(setOf("A")).adopt(outside, setOf("A"), 30))
    }
}
