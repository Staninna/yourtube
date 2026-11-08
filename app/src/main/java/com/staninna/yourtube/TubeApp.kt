package com.staninna.yourtube

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.staninna.yourtube.data.Store
import com.staninna.yourtube.page.ChannelResolver
import com.staninna.yourtube.sponsor.SponsorBlockClient
import com.staninna.yourtube.web.WebViewStartup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class TubeApp : Application() {
    /** Process-wide work that outlives any screen (channel fetches). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var store: Store
        private set
    lateinit var channels: ChannelResolver
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                PLAYBACK_CHANNEL_ID,
                getString(R.string.playback_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun processName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            File("/proc/self/cmdline").readText().substringBefore('\u0000')
        }

    companion object {
        const val PLAYBACK_CHANNEL_ID = "playback"
    }
}
