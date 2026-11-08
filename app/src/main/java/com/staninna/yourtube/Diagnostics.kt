package com.staninna.yourtube

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet

/**
 * Process-wide counters for things that should not happen but do when YouTube changes its markup:
 * a page that should yield identity facts and does not, a channel that cannot be resolved. Logged
 * under `YT-DRIFT` with a running count; Settings > About > Diagnostics shows the counts.
 */
object Diagnostics {
    private val _counts = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** How often each event happened in this process. */
    val counts: StateFlow<Map<String, Int>> = _counts.asStateFlow()

    fun count(event: String, detail: String) {
        val updated = _counts.updateAndGet { it + (event to (it[event] ?: 0) + 1) }
        Log.w("YT-DRIFT", "$event #${updated[event]}: $detail")
    }
}
