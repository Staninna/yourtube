package com.staninna.yourtube.data

import com.staninna.yourtube.YouTube
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a library item is. Library's segments are these types: Subs (channels), Videos, Playlists. */
enum class ItemType { CHANNEL, VIDEO, PLAYLIST }

/** A label from the shared pool. Items carry tag ids, so renaming a tag touches one record. */
@Serializable
data class Tag(val id: String, val name: String, val color: Int)

/** A tag color from [TagPalette], with the name TalkBack reads for it. */
data class TagColor(val name: String, val argb: Int)

/** The colors tags take in turn when created, and the choices for recoloring one. */
object TagPalette {
    /** Material tonal reds, blues, greens and ambers, legible as dots on light and dark surfaces. */
    val colors: List<TagColor> = listOf(
        TagColor("Red", 0xFFE57373.toInt()),
        TagColor("Blue", 0xFF64B5F6.toInt()),
        TagColor("Green", 0xFF81C784.toInt()),
        TagColor("Amber", 0xFFFFB74D.toInt()),
        TagColor("Purple", 0xFFBA68C8.toInt()),
        TagColor("Teal", 0xFF4DB6AC.toInt()),
        TagColor("Pink", 0xFFF06292.toInt()),
        TagColor("Brown", 0xFFA1887F.toInt()),
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
    /** Set by `Store.star` when the item is starred; the library is newest first by it. */
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
) {
    val url: String get() = YouTube.watchUrl(videoId)
    val thumbnail: String get() = YouTube.thumbnail(videoId)
}

/** How many videos Feeds shows at first and adds with each Load more. Stored by its count. */
@Serializable
enum class FeedPageSize(val count: Int) {
    @SerialName("25") P25(25),
    @SerialName("50") P50(50),
    @SerialName("100") P100(100),
    @SerialName("250") P250(250),
}

@Serializable
data class Settings(
    val hideShorts: Boolean = false,
    val dynamicColor: Boolean = true,
    val feedPageSize: FeedPageSize = FeedPageSize.P50,
)
