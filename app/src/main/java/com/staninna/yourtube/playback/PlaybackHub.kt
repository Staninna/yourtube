package com.staninna.yourtube.playback

import com.staninna.yourtube.page.NowPlaying
import com.staninna.yourtube.playback.BackgroundPlayback.Effect
import com.staninna.yourtube.playback.BackgroundPlayback.Event
import com.staninna.yourtube.web.PlayerCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Where the page and the media session meet, for the whole process (D3). The Activity attaches its
 * page and reports whether it is visible and in picture-in-picture; its ViewModel publishes what
 * plays; the media session reads [media] and sends its commands through [send]. When the session
 * lives is [BackgroundPlayback]'s call. Main thread only.
 */
class PlaybackHub(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    /** Starts the service that builds the media session. */
    private val startService: () -> Unit,
) {
    private val _media = MutableStateFlow<SessionMedia?>(null)

    /** What the media session shows. */
    val media: StateFlow<SessionMedia?> = _media.asStateFlow()

    private val _state = MutableStateFlow(BackgroundPlayback())

    /** Where D4 stands. While [BackgroundPlayback.session] holds, the page may play in the background. */
    val state: StateFlow<BackgroundPlayback> = _state.asStateFlow()

    private var page: ((PlayerCommand) -> Unit)? = null
    private var tick: Job? = null
    private var tickAt: Long? = null

    fun attach(page: (PlayerCommand) -> Unit) {
        this.page = page
    }

    /** The Activity is going, and its page with it: nothing plays any more. */
    fun detach(page: (PlayerCommand) -> Unit) {
        if (this.page !== page) return
        this.page = null
        publish(null)
        setPip(false)
    }

    fun publish(nowPlaying: NowPlaying?) {
        val next = nowPlaying?.let { SessionMedia.of(it, now()) }
        if (SessionMedia.changed(_media.value, next)) _media.value = next
        on(Event.Playback(video = nowPlaying != null, playing = nowPlaying?.playing == true))
    }

    /** Sends [command] to the page, if one is attached. */
    fun send(command: PlayerCommand) {
        page?.invoke(command)
    }

    fun setVisible(visible: Boolean) = on(Event.Visibility(visible))

    fun taskRemoved() = on(Event.TaskRemoved)

    fun setPip(inPip: Boolean) = on(Event.Pip(inPip))

    private fun on(event: Event) {
        val step = _state.value.on(event, now())
        _state.value = step.state
        when (step.effect) {
            Effect.START -> startService()
            Effect.RELEASE -> send(PlayerCommand.Pause)
            null -> Unit
        }
        scheduleTick(step.state.releaseAt)
    }

    private fun scheduleTick(at: Long?) {
        if (at == tickAt) return
        tickAt = at
        tick?.cancel()
        tick = at?.let {
            scope.launch {
                delay(at - now())
                // Forgotten first, so a tick that came early is set again.
                tickAt = null
                on(Event.Tick)
            }
        }
    }
}
