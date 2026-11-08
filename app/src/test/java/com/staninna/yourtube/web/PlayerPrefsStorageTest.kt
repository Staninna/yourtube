package com.staninna.yourtube.web

import com.staninna.yourtube.data.PlayerPrefs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPrefsStorageTest {

    /** [READ_SCRIPT]'s answer for raw localStorage values, as evaluateJavascript hands it over. */
    private fun answer(vararg values: Pair<String, String?>): String = buildJsonObject {
        PlayerPrefsStorage.KEYS.forEach { key -> put(key, JsonPrimitive(values.toMap()[key])) }
    }.toString()

    /** Values as m.youtube.com stored them, November 2026. */
    private val live = answer(
        PlayerPrefsStorage.QUALITY to """{"data":"{\"quality\":480,\"previousQuality\":360}","expiration":1825222566656,"creation":1794118566656}""",
        PlayerPrefsStorage.CAPTIONS to """{"data":"false","expiration":4916182800124,"creation":1794118800124}""",
        PlayerPrefsStorage.CAPTION_LANGUAGE to """{"data":"de-DE","expiration":1796710718145,"creation":1794118718145}""",
    )

    @Test fun readsWhatTheLivePageStored() =
        assertEquals(PlayerPrefs(quality = 480, captions = false, captionLanguage = "de-DE"), PlayerPrefsStorage.read(live))

    @Test fun missingOrMalformedValuesReadAsYouTubesDefault() {
        assertEquals(PlayerPrefs(), PlayerPrefsStorage.read(answer()))
        assertEquals(PlayerPrefs(), PlayerPrefsStorage.read("null"))
        assertEquals(PlayerPrefs(), PlayerPrefsStorage.read("not json"))
        val odd = answer(
            PlayerPrefsStorage.QUALITY to """{"data":"{\"quality\":481}"}""",
            PlayerPrefsStorage.CAPTIONS to """{"data":"yes"}""",
            PlayerPrefsStorage.CAPTION_LANGUAGE to """{"data":"<script>"}""",
        )
        assertEquals(PlayerPrefs(), PlayerPrefsStorage.read(odd))
        assertEquals(PlayerPrefs(), PlayerPrefsStorage.read(answer(PlayerPrefsStorage.QUALITY to "plain text")))
    }

    @Test fun onlyTheAllowlistedKeysAreReadOrWritten() {
        assertEquals(
            listOf("yt-player-quality", "yt-player-caption-persistence", "yt-player-caption-sticky-language"),
            PlayerPrefsStorage.KEYS,
        )
        PlayerPrefsStorage.KEYS.forEach { assertTrue(PlayerPrefsStorage.READ_SCRIPT.contains("localStorage.getItem('$it')")) }
        assertFalse(PlayerPrefsStorage.READ_SCRIPT.contains("setItem"))
    }

    @Test fun writesFreshEntriesThatReadBack() {
        val prefs = PlayerPrefs(quality = 720, captions = true, captionLanguage = "ja")
        val now = 1_800_000_000_000
        val entries = PlayerPrefsStorage.entries(prefs, now)
        assertEquals(PlayerPrefsStorage.KEYS.toSet(), entries.keys)
        for (value in entries.values) {
            val wrapper = Json.parseToJsonElement(value).jsonObject
            assertEquals(setOf("data", "expiration", "creation"), wrapper.keys)
            assertEquals("the old identity's timestamps never travel", now, wrapper["creation"]!!.jsonPrimitive.long)
            assertTrue(wrapper["expiration"]!!.jsonPrimitive.long > now)
        }
        val quality = Json.parseToJsonElement(entries.getValue(PlayerPrefsStorage.QUALITY)).jsonObject["data"]!!.jsonPrimitive.content
        assertEquals("""{"quality":720}""", quality)
        assertEquals(prefs, PlayerPrefsStorage.read(answer(*entries.toList().toTypedArray())))
    }

    @Test fun defaultsWriteNothing() {
        assertTrue(PlayerPrefsStorage.entries(PlayerPrefs(), 0).isEmpty())
        assertEquals(setOf(PlayerPrefsStorage.CAPTIONS), PlayerPrefsStorage.entries(PlayerPrefs(captions = false), 0).keys)
    }
}
