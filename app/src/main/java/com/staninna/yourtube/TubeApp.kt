package com.staninna.yourtube

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.staninna.yourtube.data.Store
import com.staninna.yourtube.page.ChannelResolver
import com.staninna.yourtube.playback.PlaybackHub
import com.staninna.yourtube.playback.PlaybackService
import com.staninna.yourtube.sponsor.SponsorBlockClient
import com.staninna.yourtube.web.WebViewStartup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import java.io.File

class TubeApp : Application() {
    /** Process-wide work that outlives any screen (channel fetches). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var store: Store
        private set
    lateinit var channels: ChannelResolver
        private set

    /** Where the page and the media session meet; outlives any one screen, as the session does. */
    lateinit var playback: PlaybackHub
        private set

    /** SponsorBlock lookups; process-wide so its cache outlives a recreated screen. */
    val segments = SponsorBlockClient()

    override fun onCreate() {
        super.onCreate()
        // The :restart trampoline process needs none of this, and must not touch WebView data.
        if (processName() != packageName) return
        // First, before anything can load WebView: every process starts with fresh WebView data.
        WebViewStartup.prepare(this)
        // One thread is the store's single writer.
        store = Store(filesDir, CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)))
        channels = ChannelResolver(store, appScope)
        playback = PlaybackHub(MainScope(), SystemClock::elapsedRealtime) {
            startService(Intent(this, PlaybackService::class.java))
        }
    }

    private fun processName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            File("/proc/self/cmdline").readText().substringBefore('\u0000')
        }
}
