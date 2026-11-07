package com.staninna.yourtube.page

import android.util.Log
import com.staninna.yourtube.YouTube
import com.staninna.yourtube.data.ChannelInfo
import com.staninna.yourtube.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolves a channel page path (`/@handle`, `/channel/UC...`, `/c/name`) to its UC id, name and
 * avatar by fetching the mobile channel page outside the WebView. Results live in the store's channel
 * cache and are refetched after [REFRESH_MS]; a failed refetch keeps the stale entry.
 *
 * At most [CONCURRENCY] fetches run at once, and concurrent calls for one path share a fetch.
 */
class ChannelResolver(
    private val store: Store,
    private val scope: CoroutineScope,
    private val fetch: suspend (path: String) -> String? = ::download,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val gate = Semaphore(CONCURRENCY)
    private val inFlight = HashMap<String, Deferred<ChannelInfo?>>()

    suspend fun resolve(path: String): ChannelInfo? {
        val cached = store.cachedChannel(path)
        if (cached != null && now() - cached.fetchedAt < REFRESH_MS) return cached.info
        return fetchShared(path).await() ?: cached?.info
    }

    private fun fetchShared(path: String): Deferred<ChannelInfo?> = synchronized(inFlight) {
        inFlight[path]?.takeIf { it.isActive } ?: scope.async {
            try {
                gate.withPermit { fetch(path) }?.let(::parse)?.also { store.cacheChannel(path, it) }
            } finally {
                synchronized(inFlight) { inFlight.remove(path) }
            }
        }.also { inFlight[path] = it }
    }

    companion object {
        const val CONCURRENCY = 2
        const val REFRESH_MS = 30L * 24 * 60 * 60 * 1000
        private const val TAG = "YT-CHANNEL"

        /** A current Chrome on Android; YouTube serves the full mobile page with metadata to it. */
        private const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"
        private const val MAX_BYTES = 4 * 1024 * 1024

        /**
         * Reads the channel's UC id, name and avatar from its page HTML, or null when the page does
         * not carry them (a consent page, an error page, or markup drift).
         */
        fun parse(html: String): ChannelInfo? {
            val id = sequenceOf(
                metaContent(html, "itemprop", "identifier"),
                RSS_CHANNEL_ID.find(html)?.groupValues?.get(1),
                metaContent(html, "property", "og:url")?.let { CHANNEL_URL_ID.find(it)?.groupValues?.get(1) },
            ).firstOrNull { it != null && CHANNEL_ID.matches(it) } ?: return null
            val name = (metaContent(html, "property", "og:title") ?: metaContent(html, "itemprop", "name"))
                ?.let(::unescapeHtml)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val avatar = metaContent(html, "property", "og:image")?.let(::unescapeHtml)
            return ChannelInfo(id, name, avatar)
        }

        /** The `content` of the first `<meta>` tag whose [attr] equals [value]. */
        private fun metaContent(html: String, attr: String, value: String): String? {
            val tag = Regex("""<meta\b[^>]*\b$attr="${Regex.escape(value)}"[^>]*>""").find(html)?.value ?: return null
            return CONTENT_ATTR.find(tag)?.groupValues?.get(1)
        }

        internal fun unescapeHtml(s: String): String = ENTITY.replace(s) { m ->
            val body = m.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") -> body.drop(2).toIntOrNull(16)?.let(::codePoint)
                body.startsWith("#") -> body.drop(1).toIntOrNull()?.let(::codePoint)
                else -> NAMED_ENTITIES[body]
            } ?: m.value
        }

        private fun codePoint(cp: Int): String? =
            if (Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else null

        private suspend fun download(path: String): String? = withContext(Dispatchers.IO) {
            val conn = URL(YouTube.HOME + path).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("User-Agent", MOBILE_UA)
                conn.setRequestProperty("Accept-Language", "en")
                // Without SOCS, channel pages answer with a redirect to the consent page.
                conn.setRequestProperty("Cookie", "SOCS=${YouTube.SOCS_REJECT_ALL}")
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "$path answered ${conn.responseCode}")
                    return@withContext null
                }
                conn.inputStream.use { input ->
                    val bytes = input.readAtMost(MAX_BYTES)
                    String(bytes, Charsets.UTF_8)
                }
            } catch (e: IOException) {
                Log.w(TAG, "fetching $path failed", e)
                null
            } finally {
                conn.disconnect()
            }
        }

        private fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (out.size() < limit) {
                val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }

        private val CHANNEL_ID = Regex("UC[A-Za-z0-9_-]{22}")
        private val RSS_CHANNEL_ID = Regex("""feeds/videos\.xml\?channel_id=(UC[A-Za-z0-9_-]{22})""")
        private val CHANNEL_URL_ID = Regex("""/channel/(UC[A-Za-z0-9_-]{22})""")
        private val CONTENT_ATTR = Regex("""\bcontent="([^"]*)"""")
        private val ENTITY = Regex("""&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);""")
        private val NAMED_ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'")
    }
}
