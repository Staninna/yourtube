package com.staninna.yourtube.sponsor

import com.staninna.yourtube.Http
import com.staninna.yourtube.HttpResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where a video's segments come from. Tests pass a fake. */
fun interface SegmentSource {
    /**
     * [videoId]'s segments on [server], or null when they could not be had (offline, a server error, an
     * unreadable answer). A video nobody marked has an empty list.
     */
    suspend fun segments(server: String, videoId: String): List<Segment>?
}

/**
 * The real source: one anonymous GET per video (a generic user agent, no cookie, no other header) and
 * an in-memory cache of the last [capacity] answers, least recently used out first. A 404 is
 * SponsorBlock's "no segments under this prefix" and is cached like any answer; failures are not, so
 * the next playback asks again.
 */
class SponsorBlockClient(
    private val get: suspend (url: String, headers: Map<String, String>) -> HttpResult = Http::get,
    private val capacity: Int = 32,
) : SegmentSource {
    private val cache = object : LinkedHashMap<String, List<Segment>>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Segment>>): Boolean = size > capacity
    }

    override suspend fun segments(server: String, videoId: String): List<Segment>? {
        val key = "$server $videoId"
        synchronized(cache) { cache[key] }?.let { return it }
        val found = when (val result = get(SponsorBlockApi.url(server, videoId), HEADERS)) {
            // A prefix answer runs to tens of kilobytes; it is read off the caller's (main) thread.
            is HttpResult.Ok -> withContext(Dispatchers.Default) { SponsorBlockApi.parse(result.body, videoId) }
            is HttpResult.Status -> if (result.code == NOT_FOUND) emptyList() else null
            is HttpResult.Failed -> null
        } ?: return null
        synchronized(cache) { cache[key] = found }
        return found
    }

    private companion object {
        const val NOT_FOUND = 404
        val HEADERS = mapOf("User-Agent" to Http.CHROME_ANDROID_UA)
    }
}
