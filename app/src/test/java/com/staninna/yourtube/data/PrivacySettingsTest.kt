package com.staninna.yourtube.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Settings > Privacy's switch, and the player choices the store keeps between identities. */
@OptIn(ExperimentalCoroutinesApi::class)
class PrivacySettingsTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun aStage8SettingsFileShowsTheNewIdentityButton() = runTest {
        File(tmp.root, "settings.json").writeText("""{"schemaVersion":1,"data":{"removeShorts":true,"pip":"manual"}}""")
        val store = Store(tmp.root, this)
        assertEquals(Settings(removeShorts = true, pip = PipMode.MANUAL, showNewIdentity = true), store.settings.value)
        assertTrue(store.corruptFiles.isEmpty())
    }

    @Test fun hidingTheButtonIsStored() = runTest {
        val store = Store(tmp.root, this)
        store.updateSettings { it.copy(showNewIdentity = false) }
        runCurrent()
        assertFalse(Store(tmp.root, this).settings.value.showNewIdentity)
    }

    @Test fun anOldBackupShowsTheButton() {
        val read = BackupCodec.decode("""{"format":"yourtube-backup","schemaVersion":1,"settings":{}}""", now = 0) as BackupRead.Valid
        assertTrue(read.backup.settings.showNewIdentity)
    }

    @Test fun playerChoicesOutliveTheProcessAndStayOutOfBackups() = runTest {
        val store = Store(tmp.root, this)
        val prefs = PlayerPrefs(quality = 1080, captions = true, captionLanguage = "nl")
        store.setPlayerPrefs(prefs)
        runCurrent()
        assertEquals(prefs, Store(tmp.root, this).playerPrefs.value)
        assertFalse(BackupCodec.encode(store.backup()).contains("captionLanguage"))
    }
}
