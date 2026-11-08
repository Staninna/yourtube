package com.staninna.yourtube.notify

import com.staninna.yourtube.data.FeedVideo
import com.staninna.yourtube.data.ItemType
import com.staninna.yourtube.data.NotifyState
import com.staninna.yourtube.data.StarredItem
import com.staninna.yourtube.data.Store
import com.staninna.yourtube.data.VideoLength
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** One background check against a real store, a fake RSS source and fake notifications. */
@OptIn(ExperimentalCoroutinesApi::class)
class NewVideoCheckTest {

    @get:Rule val tmp = TemporaryFolder()

    private var clock = 1_000_000L
    private val feeds = mutableMapOf<String, List<FeedVideo>?>()
    private val asked = mutableListOf<String>()
    private val posted = mutableListOf<List<String>>()
    private var allowed = true

    /** Fake notifications; [onPrepare] runs inside prepare, where a real one loads stills. */
    private var onPrepare: suspend () -> Unit = {}
    private val alerts = object : NewVideoAlerts {
        override fun allowed() = allowed
        override suspend fun prepare(videos: List<FeedVideo>): () -> Unit {
            onPrepare()
            return { posted += videos.map { it.videoId } }
        }
    }

    private fun TestScope.store() = Store(tmp.root, backgroundScope) { clock }

    private fun channel(id: String) = StarredItem(id, ItemType.CHANNEL, "Channel $id", url = "u", channelId = id)

    private fun video(id: String, channel: String, at: Long, author: String = "Channel $channel") = FeedVideo(id, "Title $id", author, channel, at)

    private fun check(store: Store, rss: suspend (String) -> List<FeedVideo>? = { id -> asked += id; feeds[id] }) =
        NewVideoCheck(store, rss, alerts) { clock }

    private fun TestScope.seededStore(): Store {
        val store = store()
        store.star(channel("A"))
        store.setNewVideoNotifications(true)
        feeds["A"] = listOf(video("a1", "A", at = 500))
        return store
    }

    @Test fun theFirstCheckSeedsAndTheNextOneFindsTheNewUpload() = runTest {
        val store = seededStore()
        assertTrue("the backlog never notifies", check(store).run().isEmpty())

        feeds["A"] = listOf(video("a2", "A", at = clock + 10), video("a1", "A", at = 500))
        assertEquals(listOf("a2"), check(store).run().map { it.videoId })
        assertTrue("nothing twice", check(store).run().isEmpty())
        assertEquals(listOf(listOf("a2")), posted)
        assertEquals(listOf("A", "A", "A"), asked)
    }

    @Test fun whileBlockedItFetchesNothingAndStartsOverSoAllowingAgainBringsNoFlood() = runTest {
        val store = seededStore()
        check(store).run()
        allowed = false
        clock += 10_000_000
        feeds["A"] = List(15) { video("b$it", "A", at = 600_000L + it) } + video("a1", "A", 500)
        assertTrue(check(store).run().isEmpty())
        assertEquals("no request while blocked", listOf("A"), asked)
        assertEquals(NotifyState(since = clock), store.notify.value)

        allowed = true
        assertTrue("the first allowed run seeds again", check(store).run().isEmpty())
        assertTrue(posted.isEmpty())
    }

    @Test fun cancelledWhileLoadingStillsMarksNothingSeen() = runTest {
        val store = seededStore()
        check(store).run()
        val before = store.notify.value
        feeds["A"] = listOf(video("a2", "A", at = clock + 10), video("a1", "A", at = 500))
        val never = CompletableDeferred<Unit>()
        onPrepare = { never.await() }
        val run = async(start = CoroutineStart.UNDISPATCHED) { check(store).run() }
        run.cancel()
        runCurrent()
        assertEquals("a2 stays unseen for the next run", before, store.notify.value)

        onPrepare = {}
        assertEquals(listOf("a2"), check(store).run().map { it.videoId })
        assertEquals(listOf(listOf("a2")), posted)
    }

    @Test fun cancelledOnceStillsAreInStillSavesAndPostsTogether() = runTest {
        val store = seededStore()
        check(store).run()
        feeds["A"] = listOf(video("a2", "A", at = clock + 10), video("a1", "A", at = 500))
        var job: Job? = null
        onPrepare = { job = currentCoroutineContext().job; job!!.cancel() }
        val run = async { check(store).run() }
        runCurrent()
        assertTrue(run.isCompleted)
        assertEquals("posted though cancelled", listOf(listOf("a2")), posted)
        assertEquals(listOf("a2", "a1"), store.notify.value.seen.getValue("A").ids)
    }

    @Test fun turningNotificationsOnAgainMidCheckWinsAndNothingPosts() = runTest {
        val store = seededStore()
        check(store).run()
        feeds["A"] = listOf(video("a2", "A", at = clock + 10), video("a1", "A", at = 500))
        val restarted = NewVideoCheck(store, { id -> clock += 1; store.setNewVideoNotifications(true); feeds[id] }, alerts) { clock }
        assertTrue(restarted.run().isEmpty())
        assertTrue(posted.isEmpty())
        assertTrue("the new start's state is left alone", store.notify.value.seen.isEmpty())
    }

    @Test fun theCacheMergeReadsTheCacheAndSubscriptionsAsTheyAreWhenItWrites() = runTest {
        val store = store()
        store.star(channel("A"))
        store.star(channel("B"))
        store.setNewVideoNotifications(true)
        val rss: suspend (String) -> List<FeedVideo>? = { id ->
            if (id == "B") {
                // Meanwhile Feeds learns a1's length and the user unsubscribes from B.
                store.cacheFeeds(mapOf("A" to listOf(video("a1", "A", 500).copy(length = VideoLength.Known(90))), "C" to listOf(video("c1", "C", 1))))
                store.unstar(setOf("B"))
            }
            listOf(video("${id.lowercase()}2", id, 2_000, author = ""), video("${id.lowercase()}1", id, 500))
        }
        check(store, rss).run()
        runCurrent()
        val cache = store.feeds.value
        assertEquals("the length learned mid-run is kept", listOf(null, VideoLength.Known(90)), cache.getValue("A").map { it.length })
        assertEquals("a blank channel name takes the subscription's title", "Channel A", cache.getValue("A").first().author)
        assertEquals("another writer's channel stays", listOf("c1"), cache.getValue("C").map { it.videoId })
        assertTrue("a channel unsubscribed mid-run is not put back", "B" !in cache)
    }
}
