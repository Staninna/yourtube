package com.staninna.yourtube.page

import com.staninna.yourtube.data.ChannelInfo
import com.staninna.yourtube.data.Store
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelResolverTest {

    @get:Rule val tmp = TemporaryFolder()

    private val uc = "UCXuqSBlHAE6Xw-yeJA0Tunw"
    private val fixture: String =
        checkNotNull(javaClass.classLoader?.getResource("channel/handle-page.html")).readText()

    private fun page(id: String, name: String) =
        """<meta property="og:title" content="$name"><meta itemprop="identifier" content="$id">"""

    // --- parsing ---

    @Test fun readsIdNameAndAvatarFromARealHandlePage() {
        val info = ChannelResolver.parse(fixture)!!
        assertEquals(uc, info.id)
        assertEquals("Linus Tech Tips", info.name)
        assertEquals(
            "https://yt3.googleusercontent.com/gnvYLhXy8FAlPXZ2RTrkrgj-5kyt0vdE2FUGVOiKGdEZIa-wN5A-7nwZBlWJLzUMmoh1NWAU=s900-c-k-c0x00ffffff-no-rj",
            info.avatar,
        )
    }

    @Test fun fallsBackToTheRssLinkForTheId() {
        val html = """<link rel="alternate" type="application/rss+xml" title="RSS" href="https://www.youtube.com/feeds/videos.xml?channel_id=$uc"><meta property="og:title" content="X">"""
        assertEquals(uc, ChannelResolver.parse(html)!!.id)
    }

    @Test fun decodesHtmlEntitiesInTheName() {
        val info = ChannelResolver.parse(page(uc, "Tom &amp; Jerry&#39;s &quot;Show&quot; &#x263A;"))!!
        assertEquals("Tom & Jerry's \"Show\" ☺", info.name)
        assertNull(info.avatar)
    }

    @Test fun consentOrErrorPagesYieldNothing() {
        assertNull(ChannelResolver.parse("<html><form action=\"https://consent.youtube.com/save\"></form></html>"))
        assertNull(ChannelResolver.parse(page("UCshort", "Name")))
        assertNull(ChannelResolver.parse(page(uc, " ")))
    }

    // --- fetching, sharing, caching ---

    private class FakeFetch(private val htmlFor: (String) -> String?) {
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val calls = mutableListOf<String>()
        var running = 0
        var maxRunning = 0

        suspend fun fetch(path: String): String? {
            calls += path
            running++
            maxRunning = maxOf(maxRunning, running)
            gates.getOrPut(path) { CompletableDeferred() }.await()
            running--
            return htmlFor(path)
        }
    }

    private fun TestScope.resolver(fake: FakeFetch, store: Store, now: () -> Long = { 0L }) =
        ChannelResolver(store, backgroundScope, fake::fetch, now)

    @Test fun atMostTwoFetchesRunAtOnce() = runTest {
        val fake = FakeFetch { page(uc, it) }
        val resolver = resolver(fake, Store(tmp.root, backgroundScope))
        val paths = (1..5).map { "/@c$it" }
        val results = paths.map { async { resolver.resolve(it) } }
        runCurrent()
        assertEquals(2, fake.running)

        paths.forEach { path ->
            fake.gates.getOrPut(path) { CompletableDeferred() }.complete(Unit)
            runCurrent()
        }
        assertEquals(paths, results.awaitAll().map { it!!.name })
        assertEquals(ChannelResolver.CONCURRENCY, fake.maxRunning)
    }

    @Test fun concurrentCallsForOnePathShareAFetch() = runTest {
        val fake = FakeFetch { page(uc, "Shared") }
        val resolver = resolver(fake, Store(tmp.root, backgroundScope))
        val a = async { resolver.resolve("/@same") }
        val b = async { resolver.resolve("/@same") }
        runCurrent()
        fake.gates.getValue("/@same").complete(Unit)
        assertEquals(a.await(), b.await())
        assertEquals(listOf("/@same"), fake.calls)
    }

    @Test fun freshCacheEntriesSkipTheNetworkAndStaleOnesRefetch() = runTest {
        var now = 0L
        val store = Store(tmp.root, backgroundScope) { now }
        store.cacheChannel("/@x", ChannelInfo(uc, "Cached", ""))
        val fake = FakeFetch { page(uc, "Fresh") }
        fake.gates["/@x"] = CompletableDeferred(Unit)
        val resolver = resolver(fake, store) { now }

        now = ChannelResolver.REFRESH_MS - 1
        assertEquals("Cached", resolver.resolve("/@x")!!.name)
        assertEquals(emptyList<String>(), fake.calls)

        now = ChannelResolver.REFRESH_MS + 1
        assertEquals("Fresh", resolver.resolve("/@x")!!.name)
        assertEquals("Fresh", store.cachedChannel("/@x")!!.info.name)
    }

    @Test fun aFailedRefetchKeepsTheStaleEntry() = runTest {
        var now = 0L
        val store = Store(tmp.root, backgroundScope) { now }
        store.cacheChannel("/@x", ChannelInfo(uc, "Cached", ""))
        val fake = FakeFetch { null }
        fake.gates["/@x"] = CompletableDeferred(Unit)
        now = ChannelResolver.REFRESH_MS + 1
        assertEquals("Cached", resolver(fake, store) { now }.resolve("/@x")!!.name)
    }

    @Test fun anUnknownChannelThatFailsResolvesToNull() = runTest {
        val fake = FakeFetch { "<html>consent</html>" }
        fake.gates["/@x"] = CompletableDeferred(Unit)
        assertNull(resolver(fake, Store(tmp.root, backgroundScope)).resolve("/@x"))
    }
}
