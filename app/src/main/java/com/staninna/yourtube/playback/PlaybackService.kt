package com.staninna.yourtube.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.staninna.yourtube.MainActivity
import com.staninna.yourtube.R
import com.staninna.yourtube.TubeApp
import com.staninna.yourtube.web.PlayerCommand
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Carries the media session: the media notification, lock-screen and Bluetooth or headset controls,
 * and the foreground state that keeps the process, and with it the WebView, alive in the background.
 * [PlaybackHub] starts it and says when it ends. media3 keeps it in the foreground while the video
 * plays and for [BackgroundPlayback.GRACE_MS] after it stops, so away from the app the session never
 * outlives its foreground state.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var hub: PlaybackHub
    private lateinit var session: MediaSession

    /** Headphones unplugged or Bluetooth audio gone: the video pauses instead of playing out loud. */
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = hub.send(PlayerCommand.Pause)
    }

    override fun onCreate() {
        super.onCreate()
        hub = (application as TubeApp).playback
        val player = TubePlayer(mainLooper, hub::send, SystemClock::elapsedRealtime)
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player).setSessionActivity(openApp).build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.playback_channel_name)
                .build(),
        )
        setForegroundServiceTimeoutMs(BackgroundPlayback.GRACE_MS)
        // No controller binds to this service, so the session is added here rather than in onGetSession.
        addSession(session)
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        lifecycleScope.launch { hub.media.collect(player::show) }
        lifecycleScope.launch {
            hub.state.first { !it.session }
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    /** Swiped away from recents: media3 would keep playing; D4 says pause and end the session. */
    override fun onTaskRemoved(rootIntent: Intent?) = hub.taskRemoved()

    override fun onDestroy() {
        unregisterReceiver(noisy)
        session.player.release()
        session.release()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL_ID = "playback"
    }
}
