package com.staninna.yourtube

import com.staninna.yourtube.data.Store
import com.staninna.yourtube.data.SegmentAction
import com.staninna.yourtube.sponsor.SegmentSource
import com.staninna.yourtube.web.BridgeReply
import com.staninna.yourtube.web.PageSegment
import com.staninna.yourtube.web.SegmentSkipped
import com.staninna.yourtube.web.SegmentsReply
import com.staninna.yourtube.web.UndoSkip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * SponsorBlock between the page and [source]: the page asks for a video's segments when it loads and
 * gets them with the user's action for each; a skip it reports becomes a snackbar with Undo; a change
 * to SponsorBlock settings reaches the video on screen at once. Runs on [scope], the main thread.
 */
class SponsorModel(
    private val store: Store,
    private val source: SegmentSource,
    private val scope: CoroutineScope,
    private val emit: (UiEvent) -> Unit,
) {
    private val pushes = Channel<BridgeReply>(Channel.BUFFERED)

    /** Messages for the page that it did not ask for. */
    val pagePushes: Flow<BridgeReply> = pushes.receiveAsFlow()

    /** The video the page last asked about: the one a settings change is pushed to. */
    private var lastVideo: String? = null

    init {
        scope.launch {
            store.settings.map { it.sponsorBlock }.distinctUntilChanged().drop(1).collect {
                lastVideo?.let { videoId -> pushes.send(replyFor(videoId)) }
            }
        }
    }

    fun request(videoId: String, reply: (SegmentsReply) -> Unit) {
        lastVideo = videoId
        scope.launch { reply(replyFor(videoId)) }
    }

    fun onSkipped(skip: SegmentSkipped) = emit(UiEvent.SegmentSkipped(skip.videoId, skip.category, skip.start))

    /** Seeks back to the segment; the page skips it no more in this playback. */
    fun undo(event: UiEvent.SegmentSkipped) {
        pushes.trySend(UndoSkip(event.videoId, event.start))
    }

    /**
     * [videoId]'s segments with the user's action for each; a category that is off never reaches the
     * page. No lookup at all while SponsorBlock is off or every category is; a failed one gives no
     * segments either, and the video plays as it is.
     */
    private suspend fun replyFor(videoId: String): SegmentsReply {
        val asked = store.settings.value.sponsorBlock
        val segments = if (asked.acts) lookup(asked.server, videoId) else emptyList()
        // Settings may have changed while the lookup ran; the newest decide.
        val settings = store.settings.value.sponsorBlock
        val page = segments.takeIf { settings.enabled }.orEmpty().mapNotNull { segment ->
            settings.action(segment.category).takeIf { it != SegmentAction.OFF }
                ?.let { PageSegment(segment.start, segment.end, segment.category, it) }
        }
        return SegmentsReply(videoId, page)
    }

    private suspend fun lookup(server: String, videoId: String) = try {
        source.segments(server, videoId).orEmpty()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A source bug must not take the app down from the view model's scope; the video plays as it is.
        Diagnostics.count("sponsor.error", "$videoId: $e")
        emptyList()
    }
}
