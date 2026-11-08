package com.staninna.yourtube.sponsor

import com.staninna.yourtube.data.SegmentCategory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.security.MessageDigest

/** A stretch of one video that SponsorBlock's community marked, in seconds from the start. */
data class Segment(val category: SegmentCategory, val start: Double, val end: Double)

/**
 * SponsorBlock's privacy-preserving lookup (`GET /api/skipSegments/<hash prefix>`). The server sees
 * only the first four hex digits of the video id's SHA-256, which hundreds of videos share, and answers
 * with all of their segments; the app keeps the ones for its own video. Every category is asked for,
 * so the request is the same whatever the user turned off.
 */
object SponsorBlockApi {
    /** The first four hex digits of SHA-256 over [videoId]'s UTF-8 bytes. */
    fun hashPrefix(videoId: String): String =
        MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray(Charsets.UTF_8))
            .take(2).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** The lookup URL for [videoId] on [server], a base URL without a trailing slash. */
    fun url(server: String, videoId: String): String {
        val categories = SegmentCategory.entries.joinToString(",", "[", "]") { "\"${it.apiName}\"" }
        return "$server/api/skipSegments/${hashPrefix(videoId)}?categories=" + URLEncoder.encode(categories, "UTF-8")
    }

    /**
     * [videoId]'s segments in [body], sorted by start, or null when the body is not a well-formed
     * answer. A segment the app cannot act on is dropped: an unknown category, a whole-video label or
     * highlight instead of a skip or mute, or bounds that are not a real stretch of time.
     *
     * Decoded straight into typed classes, never into a generic tree: the reader skips unknown fields
     * without recursion, and a value of the wrong shape fails at once, so no answer can run the stack
     * out however deep it nests.
     */
    fun parse(body: String, videoId: String): List<Segment>? {
        val entries = try {
            json.decodeFromString(ListSerializer(VideoEntry.serializer()), body)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        return entries.asSequence()
            .filter { it.videoID == videoId }
            .flatMap { it.segments.asSequence() }
            .mapNotNull(::toSegment)
            .distinct()
            .take(MAX_SEGMENTS)
            .sortedBy { it.start }
            .toList()
    }

    private fun toSegment(raw: RawSegment): Segment? {
        val category = SegmentCategory.fromApi(raw.category) ?: return null
        if (raw.actionType !in ACTION_TYPES || raw.segment.size != 2) return null
        val (start, end) = raw.segment
        if (!start.isFinite() || !end.isFinite() || start < 0 || end <= start) return null
        return Segment(category, start, end)
    }

    /** Skip and mute segments are stretches of time; `full`, `poi` and `chapter` are labels. */
    private val ACTION_TYPES = setOf("skip", "mute")

    /** Far more than any real video has; a hostile answer cannot fill memory or the page with more. */
    private const val MAX_SEGMENTS = 100

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private class VideoEntry(
        @SerialName("videoID") val videoID: String,
        val segments: List<RawSegment> = emptyList(),
    )

    @Serializable
    private class RawSegment(
        val category: String,
        val actionType: String = "skip",
        val segment: List<Double>,
    )
}
