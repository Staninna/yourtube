package com.staninna.yourtube.data

import com.staninna.yourtube.YouTube
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/** What a library item is. Library's segments are these types: Subs (channels), Videos, Playlists. */
enum class ItemType { CHANNEL, VIDEO, PLAYLIST }

/** A label from the shared pool. Items carry tag ids, so renaming a tag touches one record. */
@Serializable
data class Tag(val id: String, val name: String, val color: Int)

/** A tag color from [TagPalette], with the name TalkBack reads for it. */
data class TagColor(val name: String, val argb: Int)

/** The colors tags take in turn when created, and the choices for recoloring one. */
object TagPalette {
    /**
     * 32 mid tones, each at least 1.6:1 against the light surface and 2.7:1 against the dark one, so a
     * dot reads on both. The first eight are the original palette; stored tags keep their argb whatever
     * the palette holds. No two are closer than Red and Pink (0.065 apart in OKLab).
     */
    val colors: List<TagColor> = listOf(
        TagColor("Red", 0xFFE57373.toInt()),
        TagColor("Blue", 0xFF64B5F6.toInt()),
        TagColor("Green", 0xFF81C784.toInt()),
        TagColor("Amber", 0xFFFFB74D.toInt()),
        TagColor("Purple", 0xFFBA68C8.toInt()),
        TagColor("Teal", 0xFF4DB6AC.toInt()),
        TagColor("Pink", 0xFFF06292.toInt()),
        TagColor("Brown", 0xFFA1887F.toInt()),
        TagColor("Crimson", 0xFFC62828.toInt()),
        TagColor("Royal blue", 0xFF1C7ED6.toInt()),
        TagColor("Forest", 0xFF2E7D32.toInt()),
        TagColor("Mustard", 0xFFC49A0C.toInt()),
        TagColor("Violet", 0xFF8A4FC0.toInt()),
        TagColor("Petrol", 0xFF00838F.toInt()),
        TagColor("Magenta", 0xFFC2185B.toInt()),
        TagColor("Bronze", 0xFF8D6420.toInt()),
        TagColor("Tangerine", 0xFFFF7A3D.toInt()),
        TagColor("Indigo", 0xFF4A5BD0.toInt()),
        TagColor("Grass", 0xFF66AE2E.toInt()),
        TagColor("Lime", 0xFFB4C82A.toInt()),
        TagColor("Orchid", 0xFFE86AE0.toInt()),
        TagColor("Jade", 0xFF3E8E68.toInt()),
        TagColor("Mauve", 0xFFA0576F.toInt()),
        TagColor("Graphite", 0xFF737373.toInt()),
        TagColor("Rust", 0xFFC4562F.toInt()),
        TagColor("Periwinkle", 0xFF8F8FF0.toInt()),
        TagColor("Mint", 0xFF3DDC97.toInt()),
        TagColor("Olive", 0xFF7D7F1F.toInt()),
        TagColor("Lilac", 0xFFE1A0F0.toInt()),
        TagColor("Steel", 0xFF5E8FC4.toInt()),
        TagColor("Peach", 0xFFF7A597.toInt()),
        TagColor("Silver", 0xFFBDBDBD.toInt()),
    )

    /** The color the tag created after [existing] tags gets. */
    fun next(existing: Int): Int = colors[existing % colors.size].argb

    /** [argb]'s name; an imported color outside the palette is "Custom". */
    fun nameOf(argb: Int): String = colors.firstOrNull { it.argb == argb }?.name ?: "Custom"
}

/** A starred channel, video or playlist in the local library. */
@Serializable
data class StarredItem(
    val id: String, // videoId / channelId / playlistId
    val type: ItemType,
    val title: String,
    val author: String? = null,
    /** A video still or channel avatar; null until one is known. */
    val thumbnail: String? = null,
    val url: String,
    /** The channel's UC id, which builds its RSS feed; null when the page did not yield one. */
    val channelId: String? = null,
    val tagIds: Set<String> = emptySet(),
    /** Set by `Store.star` when the item is starred; the library is stored newest first by it. */
    val addedAt: Long = 0,
) {
    /**
     * The channel a starred video belongs to, as a library item to subscribe to; null for channels and
     * playlists, and for videos whose player gave no channel id or name. Its avatar is backfilled.
     */
    fun channelItem(): StarredItem? {
        if (type != ItemType.VIDEO) return null
        val id = channelId ?: return null
        val name = author?.takeIf { it.isNotBlank() } ?: return null
        return StarredItem(id = id, type = ItemType.CHANNEL, title = name, author = name, url = YouTube.channelUrl(id), channelId = id)
    }
}

@Serializable
data class Library(
    val items: List<StarredItem> = emptyList(),
    val tags: List<Tag> = emptyList(),
)

/** What the player knows about a video, confirmed for the video id it carries. */
@Serializable
data class VideoMeta(
    val videoId: String,
    val title: String,
    val author: String?,
    /** Null when the player response had no well-formed UC id (markup drift). */
    val channelId: String?,
    /** A stream on air when the player last confirmed it; its duration is then the replay window, not a length. */
    val live: Boolean = false,
)

/**
 * A watched video and where playback stands, in seconds. History doubles as the resume map: the
 * entry's position is where the video resumes (see [ResumeRules]).
 */
@Serializable
data class HistoryEntry(
    val videoId: String,
    /** Null until the player confirms the video. */
    val meta: VideoMeta? = null,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    /** When the entry last moved to the top of history. */
    val watchedAt: Long,
) {
    val url: String get() = YouTube.watchUrl(videoId)
    val thumbnail: String get() = YouTube.thumbnail(videoId)

    /** Position over duration, 0 to 1; null until the player has reported a duration. */
    val fraction: Float? get() = if (duration > 0.0) (position / duration).toFloat().coerceIn(0f, 1f) else null

    /** The video's length in seconds; null until the player reported one, and for a stream on air. */
    val length: Double? get() = duration.takeIf { it > 0.0 && meta?.live != true }
}

/** A channel's canonical id, display name and avatar, as read from its own page. */
@Serializable
data class ChannelInfo(val id: String, val name: String, val avatar: String?)

@Serializable
data class CachedChannel(val info: ChannelInfo, val fetchedAt: Long)

/**
 * One upload in Feeds, read from a channel's RSS feed or its videos page. [publishedAt] is exact from
 * RSS and rounded from the page ("3 days ago"); either way the list sorts by it. Serializable for the
 * store's feed cache, which is why it lives here and not in `feed`.
 */
@Serializable
data class FeedVideo(
    val videoId: String,
    val title: String,
    /** The channel's name; blank when RSS gave none (Feeds then shows the subscription's title). */
    val author: String,
    val channelId: String,
    val publishedAt: Long,
    /** RSS links a Short to `/shorts/<id>`. The videos page lists no Shorts, so its videos are never one. */
    val isShort: Boolean = false,
    /** The view count when it was fetched; null when the source gave none. */
    val views: Long? = null,
    /** RSS has no lengths; they come from the channel's pages. Null until those were read. */
    val length: VideoLength? = null,
) {
    val url: String get() = YouTube.watchUrl(videoId)
    val thumbnail: String get() = YouTube.thumbnail(videoId)
}

/**
 * What a channel's pages said about a feed video's length. A length never changes, so it is kept for
 * good; the other two carry when they were read, so the pages can be asked again later.
 */
@Serializable
sealed interface VideoLength {
    @Serializable
    @SerialName("known")
    data class Known(val seconds: Long) : VideoLength

    /** Streaming now: no length until the stream ends. */
    @Serializable
    @SerialName("live")
    data class Live(val checkedAt: Long) : VideoLength

    /** On neither the videos nor the live page (a premiere, a members-only video, a page that lags). */
    @Serializable
    @SerialName("unlisted")
    data class Unlisted(val checkedAt: Long) : VideoLength
}

/** How many videos Feeds shows at first and adds with each Load more. Stored by its count. */
@Serializable
enum class FeedPageSize(val count: Int) {
    @SerialName("25") P25(25),
    @SerialName("50") P50(50),
    @SerialName("100") P100(100),
    @SerialName("250") P250(250),
}

/** When a playing video moves into a picture-in-picture window. Stored by name. */
@Serializable
enum class PipMode {
    /** On leaving the app while Watch shows the video. */
    @SerialName("auto") AUTO,

    /** On leaving the app from YouTube's fullscreen only. */
    @SerialName("fullscreen") FULLSCREEN,

    /** Only from Watch's menu; leaving the app plays the sound alone. */
    @SerialName("manual") MANUAL,
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Settings(
    /**
     * Shorts hidden in pages, opened as normal videos, and left out of Feeds. Replaces stage 3's
     * `hideShorts`, which only hid them: a stored `hideShorts` reads as this switch (settings files and
     * backups alike), and the next write stores the new name.
     */
    @JsonNames("hideShorts") val removeShorts: Boolean = false,
    val dynamicColor: Boolean = true,
    val feedPageSize: FeedPageSize = FeedPageSize.P50,
    val adBlocking: AdBlocking = AdBlocking(),
    val sponsorBlock: SponsorBlockSettings = SponsorBlockSettings(),
    val pip: PipMode = PipMode.AUTO,
    /** Whether Watch's top bar shows the new-identity (mask) button. */
    val showNewIdentity: Boolean = true,
    /** The hourly check of subscribed channels that notifies about new uploads. Off until asked for. */
    val newVideoNotifications: Boolean = false,
    /** Library's order, the same for every segment. */
    val librarySort: LibrarySort = LibrarySort.RECENT,
)

/** How Library orders a segment. Stored by name. */
@Serializable
enum class LibrarySort {
    /** Newest starred first. */
    @SerialName("recent") RECENT,

    /** By title, A to Z in the device's language, ignoring case; equal titles newest first. */
    @SerialName("name") NAME,

    /**
     * Subs only: the channel whose newest upload (from Feeds) is most recent first; channels with no
     * known upload follow, newest starred first. Elsewhere it orders as [RECENT].
     */
    @SerialName("upload") LATEST_UPLOAD,
    ;

    companion object {
        /** The orders [segment]'s Sort menu offers. */
        fun offeredFor(segment: ItemType): List<LibrarySort> =
            if (segment == ItemType.CHANNEL) entries else listOf(RECENT, NAME)

        /** [stored] as [segment] can apply it: Latest upload falls back to Recently added outside Subs. */
        fun effective(stored: LibrarySort, segment: ItemType): LibrarySort =
            if (stored in offeredFor(segment)) stored else RECENT
    }
}
