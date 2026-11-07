package com.staninna.yourtube.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A media event of the main video. The page reports it over the bridge; the store records it. */
@Serializable
enum class PlaybackKind {
    @SerialName("play") PLAY,
    @SerialName("pause") PAUSE,
    @SerialName("ended") ENDED,

    /** `timeupdate`, throttled by the page to about one per second. */
    @SerialName("time") TIME,
    @SerialName("metadata") METADATA,
}
