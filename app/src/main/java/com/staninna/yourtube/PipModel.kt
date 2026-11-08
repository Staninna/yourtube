package com.staninna.yourtube

import com.staninna.yourtube.data.PipMode
import com.staninna.yourtube.data.Store
import com.staninna.yourtube.playback.PipPolicy
import com.staninna.yourtube.playback.PipSupport
import com.staninna.yourtube.playback.PlaybackHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Picture-in-picture as the shell sees it: the user's mode, whether the device allows it, whether the
 * Activity shows in a picture-in-picture window, and Watch's menu entry in Manual mode. The Activity
 * reports support and enters picture-in-picture on [entries] (`PictureInPicture`); the shell reports
 * whether Watch shows.
 */
class PipModel(private val store: Store, playback: PlaybackHub, playing: StateFlow<Boolean>, scope: CoroutineScope) {
    private val _support = MutableStateFlow(PipSupport.NO_FEATURE)
    val support: StateFlow<PipSupport> = _support.asStateFlow()

    /** Whether the Activity shows in a picture-in-picture window: only the video then. */
    val inPip: StateFlow<Boolean> = playback.state.map { it.pip }.stateIn(scope, SharingStarted.Eagerly, false)

    private val _watchShown = MutableStateFlow(false)

    /** Watch is the tab on screen, with nothing over it. */
    val watchShown: StateFlow<Boolean> = _watchShown.asStateFlow()

    /** Whether Watch's menu offers "Picture-in-picture". */
    val offersEntry: StateFlow<Boolean> = combine(store.settings, support, playing) { settings, support, playing ->
        PipPolicy.offersEntry(settings.pip, support, playing)
    }.stateIn(scope, SharingStarted.Eagerly, false)

    private val requests = Channel<Unit>(Channel.CONFLATED)

    /** Taps on Watch's "Picture-in-picture". */
    val entries: Flow<Unit> = requests.receiveAsFlow()

    fun setSupport(support: PipSupport) {
        _support.value = support
    }

    fun setWatchShown(shown: Boolean) {
        _watchShown.value = shown
    }

    fun enter() {
        requests.trySend(Unit)
    }

    fun setMode(mode: PipMode) = store.updateSettings { it.copy(pip = mode) }
}
