package com.staninna.yourtube

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.staninna.yourtube.feed.YouTubeFeedFetcher
import com.staninna.yourtube.ui.TubeRoot
import com.staninna.yourtube.ui.theme.TubeTheme
import com.staninna.yourtube.web.PlayerCommand
import com.staninna.yourtube.web.WebViewHost
import com.staninna.yourtube.web.WebViewStartup
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The one screen. Each creation is a launch with a new identity (D2): a cold start opens Feeds or
 * Library and loads nothing; after process death, or a process restart for a new identity, it
 * reloads the last URL.
 */
class MainActivity : ComponentActivity() {

    private val app: TubeApp get() = application as TubeApp
    private val vm: MainViewModel by viewModels {
        viewModelFactory { initializer { MainViewModel(app.store, app.channels, YouTubeFeedFetcher(), app.segments, app.playback) } }
    }
    private lateinit var host: WebViewHost

    /** This screen's page, as the media session reaches it. */
    private val page: (PlayerCommand) -> Unit = { host.post(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WebViewProbe.run(this) // loads WebView, so only after TubeApp's WebViewStartup

        host = WebViewHost(this, app.store.settings, vm, onRestartNeeded = ::restartForNewIdentity)
        (savedInstanceState?.getString(KEY_URL) ?: consumeRestartUrl())?.let(vm::open)
        lifecycleScope.launch { vm.openRequests.collect(host::load) }
        lifecycleScope.launch { vm.pagePushes.collect(host::post) }
        app.playback.attach(page)
        lifecycleScope.launch { app.playback.state.map { it.session }.distinctUntilChanged().collect(host::keepPlaying) }
        backMovesTaskToBack()

        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            TubeTheme(dynamicColor = settings.dynamicColor) {
                TubeRoot(vm = vm, host = host)
            }
        }
    }

    /**
     * Config changes never recreate the Activity (D2). Compose follows them by itself; YouTube's theme
     * lives in a cookie, so the WebView host follows a light/dark switch.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        host.syncTheme(playing = vm.playing.value)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        host.restorableUrl?.let { outState.putString(KEY_URL, it) }
    }

    override fun onStart() {
        super.onStart()
        app.playback.setVisible(true)
    }

    override fun onStop() {
        super.onStop()
        app.playback.setVisible(false)
        app.store.flush()
    }

    override fun onDestroy() {
        app.playback.detach(page)
        app.store.flush()
        super.onDestroy()
    }

    /**
     * Back at the root while a video plays sends the app to the background instead of closing it
     * (D4). Registered before the content, so every screen's own back handling comes first.
     */
    private fun backMovesTaskToBack() {
        val toBack = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        }
        onBackPressedDispatcher.addCallback(this, toBack)
        lifecycleScope.launch {
            app.playback.state.collect { toBack.isEnabled = it.backMovesTaskToBack }
        }
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
