package com.staninna.yourtube.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.staninna.yourtube.MainViewModel
import com.staninna.yourtube.data.BackupCodec
import com.staninna.yourtube.readAtMost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate

/** Starts an export or an import through the system's file picker. */
class BackupActions(val export: () -> Unit, val import: () -> Unit)

/**
 * The Storage Access Framework side of backups: the system picks the file, this talks to its provider,
 * and `BackupTransfer` owns the content and the size limit.
 */
@Composable
fun rememberBackupActions(vm: MainViewModel): BackupActions {
    val resolver = LocalContext.current.contentResolver
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupCodec.MIME_TYPE)) { uri ->
        uri?.let { vm.exportBackup { bytes -> write(resolver, it, bytes) } }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importBackup { open(resolver, it) } }
    }
    return remember(exporter, importer) {
        BackupActions(
            export = { exporter.launch(BackupCodec.fileName(LocalDate.now())) },
            // Some providers label JSON as plain text or bytes; the content is validated either way.
            import = { importer.launch(arrayOf(BackupCodec.MIME_TYPE, "text/plain", "application/octet-stream")) },
        )
    }
}

/*
 * Document providers throw more than IOException (IllegalArgumentException, UnsupportedOperationException,
 * IllegalStateException...) from open and write. Everything is wrapped as IOException so the backup
 * code's IOException and SecurityException catches cover every provider, and none can crash the app.
 */

private suspend fun write(resolver: ContentResolver, uri: Uri, bytes: ByteArray) = withContext(Dispatchers.IO) {
    try {
        // "w", which every provider supports; "wt" is optional and some reject it. CreateDocument made
        // the file new, so there is no older tail to truncate.
        val out = resolver.openOutputStream(uri, "w") ?: throw IOException("no stream for $uri")
        out.use { it.write(bytes) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw e
    } catch (e: SecurityException) {
        throw e
    } catch (e: Exception) {
        throw IOException("writing $uri failed", e)
    }
}

/**
 * The document's bytes, read here so every provider call sits inside the wrapping: one byte past the
 * limit at most, which is enough for `BackupTransfer` to refuse an oversized file.
 */
private suspend fun open(resolver: ContentResolver, uri: Uri): InputStream = withContext(Dispatchers.IO) {
    try {
        val input = resolver.openInputStream(uri) ?: throw IOException("no stream for $uri")
        ByteArrayInputStream(input.use { it.readAtMost(BackupCodec.MAX_BYTES + 1) })
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw e
    } catch (e: SecurityException) {
        throw e
    } catch (e: Exception) {
        throw IOException("opening $uri failed", e)
    }
}
