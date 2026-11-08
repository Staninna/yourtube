package com.staninna.yourtube

import com.staninna.yourtube.data.Backup
import com.staninna.yourtube.data.BackupCodec
import com.staninna.yourtube.data.BackupProblem
import com.staninna.yourtube.data.BackupRead
import com.staninna.yourtube.data.ImportReport
import com.staninna.yourtube.data.ItemType
import com.staninna.yourtube.data.Library
import com.staninna.yourtube.data.PlaybackKind
import com.staninna.yourtube.data.StarredItem
import com.staninna.yourtube.data.Store
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.InputStream

class BackupTransferTest {

    @get:Rule val tmp = TemporaryFolder()

    private val videoId = "dQw4w9WgXcQ"
    private lateinit var store: Store

    private fun TestScope.transfer(): BackupTransfer {
        store = Store(tmp.root, backgroundScope)
        return BackupTransfer(store) { 10_000 }
    }

    private fun file(text: String): suspend () -> InputStream = { text.byteInputStream() }

    @Test fun importMergesAndReportsTheCounts() = runTest {
        val transfer = transfer()
        val backup = Backup(library = Library(listOf(StarredItem(videoId, ItemType.VIDEO, "V", url = "x"))))
        assertEquals(UiEvent.Imported(ImportReport(1, 0, 0, 0)), transfer.import(file(BackupCodec.encode(backup))))
        assertEquals("https://m.youtube.com/watch?v=$videoId", store.library.value.items.single().url)
    }

    @Test fun badFilesReportTheirProblemAndChangeNothing() = runTest {
        val transfer = transfer()
        store.star(StarredItem(videoId, ItemType.VIDEO, "V", url = "x"))
        val before = store.library.value
        assertEquals(UiEvent.ImportFailed(BackupProblem.UNREADABLE), transfer.import { throw IOException("gone") })
        assertEquals(UiEvent.ImportFailed(BackupProblem.UNREADABLE), transfer.import { throw SecurityException("revoked") })
        assertEquals(UiEvent.ImportFailed(BackupProblem.NOT_A_BACKUP), transfer.import(file("""{"some":"json"}""")))
        assertEquals(UiEvent.ImportFailed(BackupProblem.NEWER_VERSION), transfer.import(file("""{"format":"yourtube-backup","schemaVersion":9}""")))
        assertEquals(before, store.library.value)
    }

    @Test fun anOversizedFileIsUnreadable() = runTest {
        val transfer = transfer()
        // A valid backup padded past the limit: the size alone refuses it.
        val valid = BackupCodec.encode(Backup())
        val padded = valid.dropLast(1) + ",\"pad\":\"" + "x".repeat(BackupCodec.MAX_BYTES) + "\"}"
        assertEquals(UiEvent.ImportFailed(BackupProblem.UNREADABLE), transfer.import(file(padded)))
    }

    @Test fun exportHandsTheWholeStoreToTheWriter() = runTest {
        val transfer = transfer()
        store.star(StarredItem(videoId, ItemType.VIDEO, "V", url = "https://m.youtube.com/watch?v=$videoId"))
        store.recordPlayback(PlaybackKind.TIME, videoId, 60.0, 300.0, null)
        var written: ByteArray? = null
        assertEquals(UiEvent.Exported, transfer.export { written = it })
        val read = BackupCodec.decode(written!!.decodeToString(), now = Long.MAX_VALUE) as BackupRead.Valid
        assertEquals(store.library.value, read.backup.library)
        assertEquals(store.history.value, read.backup.history)
    }

    @Test fun aFailingWriterReportsTheFailure() = runTest {
        val transfer = transfer()
        assertEquals(UiEvent.ExportFailed, transfer.export { throw IOException("full") })
        assertEquals(UiEvent.ExportFailed, transfer.export { throw SecurityException("revoked") })
    }
}
