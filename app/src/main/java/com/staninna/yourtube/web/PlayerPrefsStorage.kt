package com.staninna.yourtube.web

import com.staninna.yourtube.data.PlayerPrefs
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Where m.youtube.com keeps [PlayerPrefs]: three localStorage entries (measured November 2026), each
 * YouTube's wrapper `{"data": "...", "expiration": ms, "creation": ms}`.
 *
 * Values are read into [PlayerPrefs] and written back fresh, so nothing else from the old identity,
 * its timestamps included, reaches the new one.
 */
object PlayerPrefsStorage {
    /** `data` is JSON again: `{"quality":720,"previousQuality":360}`. Kept 360 days. */
    const val QUALITY = "yt-player-quality"

    /** `data` is `"true"` or `"false"`. Kept for good. */
    const val CAPTIONS = "yt-player-caption-persistence"

    /** `data` is a language tag. Kept 30 days. */
    const val CAPTION_LANGUAGE = "yt-player-caption-sticky-language"

    val KEYS = listOf(QUALITY, CAPTIONS, CAPTION_LANGUAGE)

    /** Picture heights YouTube offers. */
    private val QUALITIES = setOf(144, 240, 360, 480, 720, 1080, 1440, 2160, 4320)
    private val LANGUAGE = Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8}){0,3}")
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Evaluated in the page, answers a JSON object of each key's raw value or null. */
    val READ_SCRIPT = "(function(){var o={};try{" +
        KEYS.joinToString("") { "o['$it']=localStorage.getItem('$it');" } +
        "}catch(e){}return o})()"

    private val json = Json { ignoreUnknownKeys = true }

    /** [PlayerPrefs] from [READ_SCRIPT]'s answer. Anything missing or out of shape reads as null. */
    fun read(answer: String): PlayerPrefs {
        val raw = parse(answer) as? JsonObject ?: return PlayerPrefs()
        fun data(key: String): String? = (raw[key] as? JsonPrimitive)?.contentOrNull
            ?.let { parse(it) as? JsonObject }
            ?.let { (it["data"] as? JsonPrimitive)?.contentOrNull }
        val quality = ((data(QUALITY)?.let(::parse) as? JsonObject)?.get("quality") as? JsonPrimitive)?.intOrNull
        return PlayerPrefs(
            quality = quality?.takeIf { it in QUALITIES },
            captions = data(CAPTIONS)?.toBooleanStrictOrNull(),
            captionLanguage = data(CAPTION_LANGUAGE)?.takeIf { it.length <= 35 && LANGUAGE.matches(it) },
        )
    }

    /** The localStorage entries that give a new identity [prefs], created at [now]. */
    fun entries(prefs: PlayerPrefs, now: Long): Map<String, String> = buildMap {
        prefs.quality?.takeIf { it in QUALITIES }?.let { q ->
            put(QUALITY, wrap(buildJsonObject { put("quality", JsonPrimitive(q)) }.toString(), now, 360 * DAY_MS))
        }
        prefs.captions?.let { put(CAPTIONS, wrap(it.toString(), now, 36_135 * DAY_MS)) }
        prefs.captionLanguage?.takeIf { LANGUAGE.matches(it) }?.let { put(CAPTION_LANGUAGE, wrap(it, now, 30 * DAY_MS)) }
    }

    /** [entries] as the JSON object the identity script takes. */
    fun entriesJson(prefs: PlayerPrefs, now: Long): JsonObject = JsonObject(entries(prefs, now).mapValues { JsonPrimitive(it.value) })

    private fun wrap(data: String, now: Long, lifetime: Long): String = buildJsonObject {
        put("data", JsonPrimitive(data))
        put("expiration", JsonPrimitive(now + lifetime))
        put("creation", JsonPrimitive(now))
    }.toString()

    private fun parse(text: String): JsonElement? = try {
        json.parseToJsonElement(text).takeUnless { it is JsonNull }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
