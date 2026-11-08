package com.staninna.yourtube.data

import kotlinx.serialization.Serializable

/**
 * The YouTube player choices that carry over from one identity to the next. Each is a value many
 * viewers share, so none of them tells YouTube who is watching. Null means YouTube's own default.
 */
@Serializable
data class PlayerPrefs(
    /** The picture height YouTube plays at, such as 720. */
    val quality: Int? = null,
    val captions: Boolean? = null,
    /** A BCP 47 language tag, such as `de-DE`. */
    val captionLanguage: String? = null,
)
