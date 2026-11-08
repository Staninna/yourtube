package com.staninna.yourtube.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Ad layers, SponsorBlock's category and action model, server checks, and the Shorts setting's migration. */
@OptIn(ExperimentalCoroutinesApi::class)
class ContentSettingsTest {

    @get:Rule val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    // --- ad layers ---

    @Test fun layersAreOnUntilSwitchedOff() {
        val all = AdBlocking()
        assertTrue(AdLayer.entries.all(all::isOn))
        val noNetwork = all.with(AdLayer.NETWORK, on = false)
        assertFalse(noNetwork.isOn(AdLayer.NETWORK))
        assertTrue(noNetwork.isOn(AdLayer.PLAYER))
        assertEquals(all, noNetwork.with(AdLayer.NETWORK, on = true))
    }

    @Test fun aLayerAddedLaterStartsOnForStoredSettings() {
        // A file from before a layer existed stores only what was off.
        val stored = json.decodeFromString(Settings.serializer(), """{"adBlocking":{"off":["display"]}}""")
        assertEquals(setOf(AdLayer.DISPLAY), stored.adBlocking.off)
        assertTrue(stored.adBlocking.isOn(AdLayer.SAFETY_NET))
    }

    // --- SponsorBlock ---

    @Test fun defaultsSkipSponsorAndSelfPromotionAndShowTheRest() {
        val settings = SponsorBlockSettings()
        assertTrue(settings.enabled)
        assertEquals("https://sponsor.ajay.app", settings.server)
        val skipped = SegmentCategory.entries.filter { settings.action(it) == SegmentAction.SKIP }
        assertEquals(listOf(SegmentCategory.SPONSOR, SegmentCategory.SELF_PROMO), skipped)
        assertTrue(SegmentCategory.entries.minus(skipped.toSet()).all { settings.action(it) == SegmentAction.SHOW })
    }

    @Test fun onlyChangedActionsAreStored() {
        val muted = SponsorBlockSettings().with(SegmentCategory.INTRO, SegmentAction.MUTE)
        assertEquals(mapOf(SegmentCategory.INTRO to SegmentAction.MUTE), muted.actions)
        assertEquals(SegmentAction.MUTE, muted.action(SegmentCategory.INTRO))
        // Back to the default: nothing stored, so a changed default would apply.
        assertEquals(emptyMap<SegmentCategory, SegmentAction>(), muted.with(SegmentCategory.INTRO, SegmentAction.SHOW).actions)
    }

    @Test fun categoriesAndActionsAreStoredByTheirApiNames() {
        val settings = Settings(sponsorBlock = SponsorBlockSettings().with(SegmentCategory.MUSIC_OFFTOPIC, SegmentAction.OFF))
        val text = json.encodeToString(Settings.serializer(), settings)
        assertTrue(text, text.contains(""""actions":{"music_offtopic":"off"}"""))
        assertEquals(settings, json.decodeFromString(Settings.serializer(), text))
        for (category in SegmentCategory.entries) {
            assertEquals(category, json.decodeFromString(SegmentCategory.serializer(), "\"${category.apiName}\""))
            assertEquals(category, SegmentCategory.fromApi(category.apiName))
        }
        assertEquals(null, SegmentCategory.fromApi("poi_highlight"))
    }

    @Test fun sponsorBlockActsOnlyWhenOnWithACategoryNotOff() {
        assertTrue(SponsorBlockSettings().acts)
        assertFalse(SponsorBlockSettings(enabled = false).acts)
        val allOff = SegmentCategory.entries.fold(SponsorBlockSettings()) { s, c -> s.with(c, SegmentAction.OFF) }
        assertFalse(allOff.acts)
        assertTrue(allOff.with(SegmentCategory.FILLER, SegmentAction.SHOW).acts)
    }

    // --- server ---

    @Test fun serverAddressesAreCheckedAndNormalized() {
        assertEquals(ServerInput.Valid("https://sponsor.ajay.app"), ServerInput.parse("  https://sponsor.ajay.app/ "))
        assertEquals(ServerInput.Valid("https://sb.example.org:8443/mirror"), ServerInput.parse("HTTPS://SB.Example.org:8443/mirror/"))
        assertEquals(ServerInput.Invalid(ServerProblem.BLANK), ServerInput.parse("   "))
        assertEquals(ServerInput.Invalid(ServerProblem.NOT_HTTPS), ServerInput.parse("http://sponsor.ajay.app"))
        assertEquals(ServerInput.Invalid(ServerProblem.NOT_HTTPS), ServerInput.parse("sponsor.ajay.app"))
        assertEquals(ServerInput.Invalid(ServerProblem.NOT_HTTPS), ServerInput.parse("javascript:alert(1)"))
        assertEquals(ServerInput.Invalid(ServerProblem.NO_HOST), ServerInput.parse("https://"))
        assertEquals(ServerInput.Invalid(ServerProblem.NO_HOST), ServerInput.parse("https://exa mple.com"))
        assertEquals(ServerInput.Invalid(ServerProblem.NOT_A_BASE), ServerInput.parse("https://sb.example.org/?x=1"))
        assertEquals(ServerInput.Invalid(ServerProblem.NOT_A_BASE), ServerInput.parse("https://sb.example.org/#top"))
        assertEquals(ServerInput.Invalid(ServerProblem.NOT_A_BASE), ServerInput.parse("https://me@sb.example.org"))
    }

    // --- Remove all Shorts replaces Hide Shorts ---

    @Test fun storedHideShortsReadsAsRemoveShortsAndIsWrittenUnderTheNewName() {
        assertTrue(json.decodeFromString(Settings.serializer(), """{"hideShorts":true,"dynamicColor":false}""").removeShorts)
        assertFalse(json.decodeFromString(Settings.serializer(), """{"hideShorts":false}""").removeShorts)
        val written = json.encodeToString(Settings.serializer(), Settings(removeShorts = true))
        assertTrue(written.contains("\"removeShorts\":true"))
        assertFalse(written.contains("hideShorts"))
    }

    @Test fun aStage5SettingsFileMigratesInPlace() = runTest {
        File(tmp.root, "settings.json").writeText("""{"schemaVersion":1,"data":{"hideShorts":true,"dynamicColor":false,"feedPageSize":"100"}}""")
        val store = Store(tmp.root, this)
        assertEquals(Settings(removeShorts = true, dynamicColor = false, feedPageSize = FeedPageSize.P100), store.settings.value)
        assertTrue(store.corruptFiles.isEmpty())
        store.updateSettings { it.copy(dynamicColor = true) }
        runCurrent()
        val text = File(tmp.root, "settings.json").readText()
        assertTrue(text, text.contains("\"removeShorts\":true") && !text.contains("hideShorts"))
    }

    @Test fun anImportedServerPassesTheSameCheckAsTheDialog() {
        fun imported(server: String): String {
            val backup = """{"format":"yourtube-backup","schemaVersion":1,"settings":{"sponsorBlock":{"server":"$server"}}}"""
            return (BackupCodec.decode(backup, now = 0) as BackupRead.Valid).backup.settings.sponsorBlock.server
        }
        assertEquals("https://sb.example.org", imported("https://sb.example.org/"))
        for (bad in listOf("http://sb.example.org", "javascript:alert(1)", "", "https://sb.example.org/?x=1")) {
            assertEquals(bad, SponsorBlockSettings.DEFAULT_SERVER, imported(bad))
        }
    }

    @Test fun anOldBackupsHideShortsImportsAsRemoveShorts() {
        val backup = """{"format":"yourtube-backup","schemaVersion":1,"settings":{"hideShorts":true}}"""
        val read = BackupCodec.decode(backup, now = 0) as BackupRead.Valid
        assertTrue(read.backup.settings.removeShorts)
    }
}
