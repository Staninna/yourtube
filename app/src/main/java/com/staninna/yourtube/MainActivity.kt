package com.staninna.yourtube

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.staninna.yourtube.ui.TubeRoot
import com.staninna.yourtube.ui.TubeTheme
import com.staninna.yourtube.web.WebViewHost
import com.staninna.yourtube.web.WebViewStartup
import kotlinx.coroutines.launch

/**
 * The one screen. Each creation is a launch with a new identity (D2): a cold start opens Feeds or
 * Library and loads nothing; after process death, or a process restart for a new identity, it
 * reloads the last URL.
 */
class MainActivity : ComponentActivity() {

    private val app: TubeApp get() = application as TubeApp
    private val vm: MainViewModel by viewModels {
        viewModelFactory { initializer { MainViewModel(app.store, app.channels) } }
    }
    private lateinit var host: WebViewHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WebViewProbe.run(this) // loads WebView, so only after TubeApp's WebViewStartup

        host = WebViewHost(this, app.store.settings, vm, onRestartNeeded = ::restartForNewIdentity)
        (savedInstanceState?.getString(KEY_URL) ?: consumeRestartUrl())?.let(vm::open)
        lifecycleScope.launch { vm.openRequests.collect(host::load) }

        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            TubeTheme(dynamicColor = settings.dynamicColor) {
                TubeRoot(vm = vm, host = host)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        host.restorableUrl?.let { outState.putString(KEY_URL, it) }
    }

    override fun onPause() {
        super.onPause()
        // Keep audio going when backgrounded: hold the process with a foreground service while
        // something is playing.
        if (vm.playing.value && !isChangingConfigurations) {
            ContextCompat.startForegroundService(this, Intent(this, PlaybackService::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        stopService(Intent(this, PlaybackService::class.java))
    }

    override fun onStop() {
        super.onStop()
        app.store.flush()
    }

    override fun onDestroy() {
        stopService(Intent(this, PlaybackService::class.java))
        app.store.flush()
        super.onDestroy()
    }

    /**
     * The URL a process restart asked for. Read once: a later relaunch from recents replays the same
     * intent and must start fresh instead.
     */
    private fun consumeRestartUrl(): String? {
        val url = intent.getStringExtra(WebViewStartup.EXTRA_URL)
        intent.removeExtra(WebViewStartup.EXTRA_URL)
        return url.takeIf { intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0 }
    }

    private fun restartForNewIdentity(url: String?) {
        lifecycleScope.launch {
            app.store.flush().join()
            WebViewStartup.restartProcess(this@MainActivity, url)
        }
    }

    private companion object {
        const val KEY_URL = "url"
    }
}
