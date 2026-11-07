package com.staninna.yourtube.data

import com.staninna.yourtube.YouTube
import kotlinx.serialization.Serializable

enum class ItemType { CHANNEL, VIDEO, PLAYLIST }

/** A label from the shared pool. Items carry tag ids, so renaming a tag touches one record. */
@Serializable
data class Tag(val id: String, val name: String, val color: Int)

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
    /** Set by `Store.toggleStar` when the item is starred. */
    val addedAt: Long = 0,
)

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
}

/** A channel's canonical id, display name and avatar, as read from its own page. */
@Serializable
data class ChannelInfo(val id: String, val name: String, val avatar: String?)

@Serializable
data class CachedChannel(val info: ChannelInfo, val fetchedAt: Long)

/** A newest-upload entry parsed from a channel's RSS feed. */
@Serializable
data class FeedVideo(
    val videoId: String,
    val title: String,
    val author: String,
    val channelId: String,
    val thumbnail: String,
    val publishedAt: Long,
    val url: String,
)

@Serializable
data class Settings(
    val hideShorts: Boolean = false,
    val dynamicColor: Boolean = true,
)
