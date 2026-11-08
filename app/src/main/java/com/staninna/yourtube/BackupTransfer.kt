package com.staninna.yourtube

import com.staninna.yourtube.data.BackupCodec
import com.staninna.yourtube.data.BackupProblem
import com.staninna.yourtube.data.BackupRead
import com.staninna.yourtube.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream

/**
 * Moves the store into and out of a backup file. The caller supplies the file's streams (the system
 * picker's document) and shows the returned event. Nothing here throws on a bad file or a failing
 * provider: the file side wraps whatever its provider throws as [IOException].
 */
class BackupTransfer(private val store: Store, private val now: () -> Long = System::currentTimeMillis) {

    /** Encodes the whole store and hands the bytes to [write]. */
    suspend fun export(write: suspend (ByteArray) -> Unit): UiEvent {
        val bytes = withContext(Dispatchers.Default) { BackupCodec.encode(store.backup()).encodeToByteArray() }
        return try {
            write(bytes)
            UiEvent.Exported
        } catch (_: IOException) {
            UiEvent.ExportFailed
        } catch (_: SecurityException) {
            UiEvent.ExportFailed
        }
    }

    /**
     * Reads the file [open] gives, refusing one over [BackupCodec.MAX_BYTES], validates it and merges
     * it into the store. A bad file changes nothing.
     */
    suspend fun import(open: suspend () -> InputStream): UiEvent {
        val bytes = try {
            withContext(Dispatchers.IO) { open().use { it.readAtMost(BackupCodec.MAX_BYTES + 1) } }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
        if (bytes == null || bytes.size > BackupCodec.MAX_BYTES) return UiEvent.ImportFailed(BackupProblem.UNREADABLE)
        return when (val read = withContext(Dispatchers.Default) { BackupCodec.decode(bytes.decodeToString(), now()) }) {
            is BackupRead.Invalid -> UiEvent.ImportFailed(read.problem)
            is BackupRead.Valid -> UiEvent.Imported(store.import(read.backup).copy(skipped = read.skipped))
        }
    }
}
