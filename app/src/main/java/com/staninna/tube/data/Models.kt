package com.staninna.tube.data

import kotlinx.serialization.Serializable

enum class ItemType { CHANNEL, VIDEO, PLAYLIST }

/** A starred channel, video or playlist in the local library. */
@Serializable
data class StarredItem(
    val id: String,              // videoId / channelId / playlistId
    val type: ItemType,
    val title: String,
    val author: String = "",
    val thumbnail: String = "",
    val url: String,
    val channelId: String = "",  // the channel's id, used to build its RSS feed
    val folderId: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class Folder(
    val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** A watched video, kept locally so it can be reopened and resumed. */
@Serializable
data class HistoryEntry(
    val videoId: String,
    val title: String,
    val author: String = "",
    val thumbnail: String = "",
    val url: String,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val updatedAt: Long = System.currentTimeMillis(),
)

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
data class LibraryState(
    val items: List<StarredItem> = emptyList(),
    val folders: List<Folder> = emptyList(),
)

@Serializable
data class Settings(
    val hideShorts: Boolean = false,
    val dynamicColor: Boolean = true,
)
