package com.staninna.yourtube.feed

import com.staninna.yourtube.HttpResult
import kotlinx.coroutines.delay

/**
 * How many times a request is tried and how long to wait between tries: [firstDelayMs], doubled after
 * each try. A missing connection is never retried, and neither is a status outside [retryStatuses].
 */
data class RetryPolicy(val attempts: Int, val firstDelayMs: Long, val retryStatuses: Set<Int>) {

    /** The wait before another try after [tried] tries, the last ending in [result]; null to stop. */
    fun nextDelay(tried: Int, result: HttpResult): Long? {
        if (tried >= attempts) return null
        val retry = when (result) {
            is HttpResult.Ok -> false
            is HttpResult.Status -> result.code in retryStatuses
            is HttpResult.Failed -> !result.offline
        }
        return if (retry) firstDelayMs shl (tried - 1) else null
    }

    /** Runs [request] until it succeeds or this policy stops; returns the last result. */
    suspend fun run(request: suspend () -> HttpResult): HttpResult {
        var tried = 0
        while (true) {
            val result = request()
            tried++
            delay(nextDelay(tried, result) ?: return result)
        }
    }

    companion object {
        /** 429 is not here: it asks us to slow down, and a quick retry would only add load. */
        private val SERVER_ERRORS = setOf(500, 502, 503, 504)

        /**
         * The feed server flaps between 200, 404 and 500 for the same channel within seconds, so a
         * second try often lands, and 404 counts as flapping. When it is down for hours, the second
         * 404 sends the channel to its videos page.
         */
        val RSS = RetryPolicy(attempts = 2, firstDelayMs = 400, retryStatuses = SERVER_ERRORS + 404)

        /** A videos page that answers 404 has no channel behind it; only a server error is worth a retry. */
        val PAGE = RetryPolicy(attempts = 3, firstDelayMs = 1_000, retryStatuses = SERVER_ERRORS)
    }
}
