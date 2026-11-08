package com.staninna.yourtube

import android.os.SystemClock
import com.staninna.yourtube.playback.PlaybackHub
import kotlinx.coroutines.MainScope

/** A playback hub for screen tests: no media session service is ever started. */
fun testHub() = PlaybackHub(MainScope(), SystemClock::elapsedRealtime, startService = {})
