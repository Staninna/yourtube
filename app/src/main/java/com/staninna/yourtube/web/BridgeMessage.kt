package com.staninna.yourtube.web

import android.util.Log
import com.staninna.yourtube.data.PlaybackKind
import com.staninna.yourtube.data.SegmentAction
import com.staninna.yourtube.data.SegmentCategory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Messages the page scripts post to `TubeBridge`, decoded once here at the edge. The `type` field
 * picks the subclass. The page sends raw facts only; meaning is derived in Kotlin (`PageIdentity`).
 */
@Serializable
sealed interface BridgeMessage {
    companion object {
        /** Decodes one message, or returns null (and logs) for anything that does not fit the schema. */
        fun decode(text: String): BridgeMessage? = try {
            json.decodeFromString(serializer(), text)
        } catch (e: SerializationException) {
            Log.w(TAG, "dropping malformed bridge message: ${text.take(200)}", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "dropping malformed bridge message: ${text.take(200)}", e)
            null
        }

        fun encode(reply: BridgeReply): String = json.encodeToString(BridgeReply.serializer(), reply)

        private const val TAG = "YT-BRIDGE"
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }
}

/** What the page shows right now, as raw facts. [url] is the `location.href` the facts describe. */
@Serializable
@SerialName("page")
data class PageReport(
    val url: String,
    val reason: Reason,
    /** `link[rel=canonical]` href, the one head tag YouTube keeps current on SPA navigation. */
    val canonical: String? = null,
    /** `document.title`. */
    val title: String = "",
    /** `movie_player.getPlayerResponse().videoDetails`, or null when there is no player response. */
    val player: PlayerFacts? = null,
    /** The player response's `playabilityStatus`, or null when there is none. */
    val playability: Playability? = null,
) : BridgeMessage {
    @Serializable
    enum class Reason {
        @SerialName("load") LOAD,
        @SerialName("navigate") NAVIGATE,

        /** Sent on the video's `loadedmetadata`, when the player has caught up with the URL. */
        @SerialName("metadata") METADATA,
    }
}

/** The `videoDetails` fields the app reads; each is null when the player response lacks it. */
@Serializable
data class PlayerFacts(
    val videoId: String? = null,
    val channelId: String? = null,
    val title: String? = null,
    val author: String? = null,
    /** `videoDetails.isLive`: a stream on air. */
    val live: Boolean = false,
)

/**
 * A player response's `playabilityStatus`: [status] such as `OK` or `LOGIN_REQUIRED`, its [reason] as
 * YouTube words it, and the response's `videoDetails.videoId`, which a refused response may lack.
 */
@Serializable
data class Playability(val status: String? = null, val reason: String? = null, val videoId: String? = null)

/** A media event of the main video. Ads are filtered out by the page script. */
@Serializable
@SerialName("playback")
data class PlaybackEvent(
    val kind: PlaybackKind,
    val videoId: String,
    val position: Double,
    val duration: Double,
    /**
     * The video element's own `paused` at the event. Messages from a page script without the field
     * read pause and end as paused and everything else as playing.
     */
    val paused: Boolean = kind == PlaybackKind.PAUSE || kind == PlaybackKind.ENDED,
    /** The video element's `playbackRate`. */
    val rate: Double = 1.0,
) : BridgeMessage

/** The page asks where [videoId] should start. Answered with a [ResumeReply]. */
@Serializable
@SerialName("resume")
data class ResumeRequest(val videoId: String) : BridgeMessage

/** The page asks for [videoId]'s SponsorBlock segments when the video loads. Answered with a [SegmentsReply]. */
@Serializable
@SerialName("segments")
data class SegmentsRequest(val videoId: String) : BridgeMessage

/** The page skipped [videoId]'s segment of [category] from [start] to [end], in seconds. */
@Serializable
@SerialName("skipped")
data class SegmentSkipped(val videoId: String, val category: SegmentCategory, val start: Double, val end: Double) : BridgeMessage

/**
 * The viewer muted or unmuted the video themselves. YouTube's own muted start, the ad safety net and
 * SponsorBlock's mute are not the viewer and send nothing.
 */
@Serializable
@SerialName("mute")
data class ViewerMute(val muted: Boolean) : BridgeMessage

/** The page answered YouTube's in-page consent dialog with "Reject all". */
@Serializable
@SerialName("consent")
data object ConsentRejected : BridgeMessage

/** An ad played on [url] despite the other ad layers. */
@Serializable
@SerialName("adLeak")
data class AdLeak(val url: String) : BridgeMessage

/**
 * Where the main video shows, for picture-in-picture: the picture's size in video pixels, and its
 * element's box in CSS pixels of a viewport [viewportWidth] CSS pixels wide (`innerWidth`, which spans
 * the WebView; `devicePixelRatio` misses the page's own rounding). Sent with the video's reports
 * whenever any of it changed.
 */
@Serializable
@SerialName("videoBox")
data class VideoBox(
    val videoWidth: Int,
    val videoHeight: Int,
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
    val viewportWidth: Double,
) : BridgeMessage

/**
 * A vertical swipe on the video. [amount] is how far it has gone up since it started, as a share of
 * the player's height (down is negative); the app moves [control] by as much of its range.
 */
@Serializable
@SerialName("swipe")
data class Swipe(val control: Control, val phase: Phase, val amount: Double = 0.0) : BridgeMessage {
    @Serializable
    enum class Control {
        /** The right half of the video. */
        @SerialName("volume") VOLUME,

        /** The left half of the video. */
        @SerialName("brightness") BRIGHTNESS,
    }

    @Serializable
    enum class Phase {
        @SerialName("start") START,
        @SerialName("move") MOVE,
        @SerialName("end") END,
    }
}

/** Messages the app sends back to the page, with the same `type` discriminator. */
@Serializable
sealed interface BridgeReply

/**
 * Native's answer to a [ResumeRequest]: where to start, null for the beginning (a `t=` in the URL wins
 * either way), and whether the viewer wants it muted. After a new identity, [carried] is how the video
 * played under the old one.
 */
@Serializable
@SerialName("resume")
data class ResumeReply(val videoId: String, val position: Double?, val muted: Boolean, val carried: Carried? = null) : BridgeReply

/** How a video played when its identity was replaced, for the new identity to take over. */
@Serializable
data class Carried(val rate: Double, val paused: Boolean)

/**
 * [videoId]'s segments with what to do at each, in order of start. Answers a [SegmentsRequest], and is
 * pushed again when SponsorBlock settings change. Empty when there are none, SponsorBlock is off, or
 * they could not be fetched: the video then plays as it is.
 */
@Serializable
@SerialName("segments")
data class SegmentsReply(val videoId: String, val segments: List<PageSegment>) : BridgeReply

/** One segment as the page acts on it; never [SegmentAction.OFF]. */
@Serializable
data class PageSegment(val start: Double, val end: Double, val category: SegmentCategory, val action: SegmentAction)

/** The viewer undid the skip of [videoId]'s segment starting at [start]: seek back, and skip it no more. */
@Serializable
@SerialName("undoSkip")
data class UndoSkip(val videoId: String, val start: Double) : BridgeReply

/** What the media session asks of the page's main video. */
@Serializable
sealed interface PlayerCommand : BridgeReply {
    @Serializable
    @SerialName("play")
    data object Play : PlayerCommand

    @Serializable
    @SerialName("pause")
    data object Pause : PlayerCommand

    /** Seeks [videoId] to [position] seconds; a page that has moved on to another video ignores it. */
    @Serializable
    @SerialName("seek")
    data class Seek(val videoId: String, val position: Double) : PlayerCommand
}
