package com.staninna.yourtube.playback

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.staninna.yourtube.page.NowPlaying
import com.staninna.yourtube.web.PlayerCommand

/**
 * The media session's player, standing in for the page's `<video>`: its state is what [show] last
 * got from the hub, and its commands go to the page through [send]. Chromium takes audio focus for the
 * video itself (stage 0), so this player never asks for it.
 */
@OptIn(UnstableApi::class)
class TubePlayer(
    looper: Looper,
    private val send: (PlayerCommand) -> Unit,
    private val now: () -> Long,
) : SimpleBasePlayer(looper) {
    private val handler = Handler(looper)
    private var media: SessionMedia? = null

    /** A command the page has not answered yet; media3 shows its expected outcome meanwhile. */
    private var awaiting: SettableFuture<Unit>? = null

    /** Shows [next], which also answers a command waiting on the page. */
    fun show(next: SessionMedia?) {
        media = next
        answered()
        invalidateState()
    }

    override fun getState(): State = stateOf(media, now())

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        send(if (playWhenReady) PlayerCommand.Play else PlayerCommand.Pause)
        return awaitPage()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val videoId = media?.videoId ?: return Futures.immediateVoidFuture()
        send(PlayerCommand.Seek(videoId, positionMs / 1000.0))
        return awaitPage()
    }

    override fun handleRelease(): ListenableFuture<*> {
        answered()
        return Futures.immediateVoidFuture()
    }

    private fun awaitPage(): ListenableFuture<*> {
        answered()
        val future = SettableFuture.create<Unit>()
        awaiting = future
        // A page that never answers (an ad holding the player, a page gone) must not freeze the controls.
        handler.postDelayed({ if (awaiting === future) answered() }, PAGE_ANSWER_MS)
        return future
    }

    private fun answered() {
        awaiting?.set(Unit)
        awaiting = null
    }

    companion object {
        private const val PAGE_ANSWER_MS = 1_500L

        /** Play and pause, seeking within the video, and reading it. No next or previous: the page has no queue to offer. */
        private val COMMANDS = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_RELEASE,
        ).build()

        /**
         * The player state for [media] at [now]. A seek the page made needs no flag: media3 reports any
         * position that its own running clock does not explain as a discontinuity.
         */
        fun stateOf(media: SessionMedia?, now: Long): State {
            if (media == null) return State.Builder().setAvailableCommands(Player.Commands.Builder().add(Player.COMMAND_RELEASE).build()).build()
            val metadata = MediaMetadata.Builder()
                .setTitle(media.title)
                .setArtist(media.author)
                .setArtworkUri(Uri.parse(media.thumbnail))
                .build()
            val item = MediaItemData.Builder(media.videoId)
                .setMediaItem(MediaItem.Builder().setMediaId(media.videoId).build())
                .setMediaMetadata(metadata)
                .setDurationUs(media.durationMs?.let { it * 1000 } ?: C.TIME_UNSET)
                .setIsSeekable(media.durationMs != null)
                .build()
            val playing = media.status == NowPlaying.Status.PLAYING
            val position = media.positionAt(now)
            return State.Builder()
                .setAvailableCommands(COMMANDS)
                .setPlaylist(listOf(item))
                .setPlaybackState(if (media.status == NowPlaying.Status.ENDED) Player.STATE_ENDED else Player.STATE_READY)
                .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackParameters(PlaybackParameters(media.rate))
                .setContentPositionMs(
                    if (playing) PositionSupplier.getExtrapolating(position, media.rate) else PositionSupplier.getConstant(position),
                )
                .build()
        }
    }
}
