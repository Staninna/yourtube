package com.staninna.yourtube.playback

/**
 * D4 without picture-in-picture: when the media session, and the foreground service that carries it,
 * starts and ends, and what back at Watch's root does. The Activity, the page and the service report
 * [Event]s; [on] answers with the next state and what to do about the session. Times are the hub's
 * clock, in ms.
 *
 * - The video playing while the Activity shows starts the session. Nothing starts it while the
 *   Activity is hidden, so the service is only ever started from the foreground.
 * - Leaving the app, the screen going off and switching to a native tab change nothing: what plays
 *   keeps playing, and the session with it.
 * - Once the video stops (paused, ended, the next one not started yet), the session lasts [GRACE_MS]
 *   while the Activity is hidden. The service keeps its foreground state that long after a stop too.
 * - With no video in Watch, or the task swiped away from recents, the session ends at once, and the
 *   page is paused.
 * - Back at Watch's root while the video plays moves the task to the back instead of finishing the
 *   Activity, and the WebView with it. Android 12 does that by itself only for a task started from the
 *   launcher, not one opened from the notification or a link.
 */
data class BackgroundPlayback(
    /** The Activity is started: on screen, not behind Home or a switched-off screen. */
    val visible: Boolean = false,
    /** Watch shows a video. */
    val video: Boolean = false,
    val playing: Boolean = false,
    val session: Boolean = false,
    /** When the video last stopped playing. */
    val stoppedAt: Long = 0,
) {
    sealed interface Event {
        data class Visibility(val visible: Boolean) : Event
        /** [playing] is never true without a [video]. */
        data class Playback(val video: Boolean, val playing: Boolean) : Event
        data object TaskRemoved : Event

        /** Time has passed; the hub sends it at [releaseAt]. */
        data object Tick : Event
    }

    enum class Effect {
        /** Start the service, which builds the session. */
        START,

        /** Pause the page, end the session and stop the service. */
        RELEASE,
    }

    data class Step(val state: BackgroundPlayback, val effect: Effect?)

    val backMovesTaskToBack: Boolean get() = playing

    /** When the session ends unless something changes first; null when nothing is counting down. */
    val releaseAt: Long? get() = if (session && video && !playing && !visible) stoppedAt + GRACE_MS else null

    fun on(event: Event, now: Long): Step {
        val next = when (event) {
            is Event.Visibility -> copy(visible = event.visible)
            is Event.Playback ->
                copy(video = event.video, playing = event.playing, stoppedAt = if (playing && !event.playing) now else stoppedAt)
            Event.TaskRemoved -> return Step(copy(session = false), Effect.RELEASE.takeIf { session })
            Event.Tick -> this
        }
        return next.settle(now)
    }

    private fun settle(now: Long): Step = when {
        session && !keepsSession(now) -> Step(copy(session = false), Effect.RELEASE)
        !session && visible && playing -> Step(copy(session = true), Effect.START)
        else -> Step(this, null)
    }

    private fun keepsSession(now: Long) = video && releaseAt.let { it == null || now < it }

    companion object {
        const val GRACE_MS = 10 * 60_000L
    }
}
