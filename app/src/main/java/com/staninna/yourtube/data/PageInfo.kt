package com.staninna.yourtube.data

import kotlinx.serialization.Serializable

/** What the WebView is currently showing, reported by inject.js. */
@Serializable
data class PageInfo(
    val url: String = "",
    val type: String = "other",   // video | channel | playlist | other
    val id: String = "",
    val title: String = "",
    val author: String = "",
    val channelId: String = "",
    val thumbnail: String = "",
) {
    val starrable: Boolean get() = type == "video" || type == "channel" || type == "playlist"

    fun toStarredItem() = StarredItem(
        id = id,
        type = when (type) {
            "channel" -> ItemType.CHANNEL
            "playlist" -> ItemType.PLAYLIST
            else -> ItemType.VIDEO
        },
        title = title.ifBlank { url },
        author = author,
        thumbnail = thumbnail.ifBlank { if (type == "video") "https://i.ytimg.com/vi/$id/hqdefault.jpg" else "" },
        url = url,
        channelId = channelId,
    )
}

/** Reported watch progress from inject.js. */
@Serializable
data class WatchReport(
    val videoId: String = "",
    val title: String = "",
    val author: String = "",
    val channelId: String = "",
    val thumbnail: String = "",
    val url: String = "",
    val position: Double = 0.0,
    val duration: Double = 0.0,
) {
    fun toHistoryEntry() = HistoryEntry(
        videoId = videoId,
        title = title,
        author = author,
        thumbnail = thumbnail,
        url = url.ifBlank { "https://m.youtube.com/watch?v=$videoId" },
        position = position,
        duration = duration,
    )
}
