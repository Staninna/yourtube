package com.staninna.yourtube.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The picture-in-picture setting in settings files and backups, old ones included. */
@OptIn(ExperimentalCoroutinesApi::class)
class PipModeTest {

    @get:Rule val tmp = TemporaryFolder()

    /** As the store writes: defaults included. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test fun automaticIsTheDefault() = assertEquals(PipMode.AUTO, Settings().pip)

    @Test fun eachModeIsStoredByName() {
        val names = mapOf(PipMode.AUTO to "auto", PipMode.FULLSCREEN to "fullscreen", PipMode.MANUAL to "manual")
        for ((mode, name) in names) {
            val text = json.encodeToString(Settings.serializer(), Settings(pip = mode))
            assertTrue(text, text.contains("\"pip\":\"$name\""))
            assertEquals(mode, json.decodeFromString(Settings.serializer(), text).pip)
        }
    }

    @Test fun aSettingsFileFromBeforePipReadsAsAutomatic() = runTest {
        File(tmp.root, "settings.json").writeText("""{"schemaVersion":1,"data":{"removeShorts":true,"feedPageSize":"100"}}""")
        val store = Store(tmp.root, this)
        assertTrue(store.corruptFiles.isEmpty())
        assertEquals(Settings(removeShorts = true, feedPageSize = FeedPageSize.P100), store.settings.value)
        store.updateSettings { it.copy(pip = PipMode.MANUAL) }
        runCurrent()
        assertEquals(PipMode.MANUAL, Store(tmp.root, this).settings.value.pip)
    }

    @Test fun aBackupFromBeforePipImportsAsAutomatic() {
        val backup = """{"format":"yourtube-backup","schemaVersion":1,"settings":{"dynamicColor":false}}"""
        val read = BackupCodec.decode(backup, now = 0) as BackupRead.Valid
        assertEquals(Settings(dynamicColor = false), read.backup.settings)
    }

    @Test fun aBackupCarriesTheMode() {
        val backup = """{"format":"yourtube-backup","schemaVersion":1,"settings":{"pip":"fullscreen"}}"""
        val read = BackupCodec.decode(backup, now = 0) as BackupRead.Valid
        assertEquals(PipMode.FULLSCREEN, read.backup.settings.pip)
    }
}
