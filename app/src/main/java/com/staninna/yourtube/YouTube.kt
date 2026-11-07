package com.staninna.yourtube

/** The one home for YouTube URLs, origins and the consent value the app relies on. */
object YouTube {
    const val HOME = "https://m.youtube.com"

    /** Origins the bridge and the page scripts are exposed to. */
    val ORIGINS = setOf("https://m.youtube.com", "https://www.youtube.com")

    /** Hosts whose pages are YouTube content (as opposed to consent or captcha pages). */
    val CONTENT_HOSTS = setOf("m.youtube.com", "www.youtube.com", "youtube.com")

    /**
     * The `SOCS` value YouTube sets after "Reject all" on its consent page (stage 0 finding f).
     * Seeding it skips the consent interstitial without accepting tracking.
     */
    const val SOCS_REJECT_ALL =
        "CAESNQgDEitib3FfaWRlbnRpdHlmcm9udGVuZHVpc2VydmVyXzIwMjYxMTA2LjA3X3AwGgJlbiACGgYIgNS51wY"

    fun watchUrl(videoId: String) = "$HOME/watch?v=$videoId"
    fun channelUrl(channelId: String) = "$HOME/channel/$channelId"
    fun playlistUrl(playlistId: String) = "$HOME/playlist?list=$playlistId"
    fun thumbnail(videoId: String) = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
}
