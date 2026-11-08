package com.staninna.yourtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.URL
import java.net.UnknownHostException

/** What one GET came back with. */
sealed interface HttpResult {
    /** HTTP 200 with its body, cut at the size limit. */
    data class Ok(val body: String) : HttpResult

    /** Any other status. */
    data class Status(val code: Int) : HttpResult

    /** No answer at all; [offline] when the device has no network or cannot resolve the host. */
    data class Failed(val offline: Boolean) : HttpResult
}

/**
 * Plain GETs to YouTube outside the WebView, shaped like a current Chrome on Android with the
 * consent cookie set: without `SOCS` channel pages answer with a redirect to the consent page. No
 * account and no other cookie, so every request is anonymous. Used by the channel resolver and Feeds.
 */
object YouTubeHttp {
    /** A current Chrome on Android; YouTube serves the full mobile page with metadata to it. */
    const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"

    /** A mobile channel page is about 550 KB; anything past this is not a page we can read. */
    const val MAX_BYTES = 4 * 1024 * 1024

    /**
     * One GET. Cancelling the caller disconnects the connection, so a blocked read ends at once
     * instead of at its timeout.
     */
    suspend fun get(url: String): HttpResult = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        // Blocking socket reads ignore coroutine cancellation; this watcher turns it into a disconnect.
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                conn.disconnect()
            }
        }
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", MOBILE_UA)
            // English pages, so relative dates ("3 days ago") read the same everywhere.
            conn.setRequestProperty("Accept-Language", "en")
            conn.setRequestProperty("Cookie", "SOCS=${YouTube.SOCS_REJECT_ALL}")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) return@withContext HttpResult.Status(code)
            HttpResult.Ok(conn.inputStream.use { String(it.readAtMost(MAX_BYTES), Charsets.UTF_8) })
        } catch (e: IOException) {
            // A refused connection (ConnectException) is the server's side, so another source may work.
            HttpResult.Failed(offline = e is UnknownHostException || e is NoRouteToHostException)
        } finally {
            watcher.cancel()
            conn.disconnect()
        }
    }
}
