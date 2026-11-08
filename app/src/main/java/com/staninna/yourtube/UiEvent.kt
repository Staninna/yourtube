package com.staninna.yourtube

import com.staninna.yourtube.data.BackupProblem
import com.staninna.yourtube.data.HistoryEntry
import com.staninna.yourtube.data.ImportReport
import com.staninna.yourtube.data.SegmentCategory
import com.staninna.yourtube.data.StarredItem

/** Something the user should hear about once, in the snackbar. */
sealed interface UiEvent {
    /** A change the snackbar offers to take back; [MainViewModel.undo] does. */
    sealed interface Undoable : UiEvent

    data class HistoryCleared(val entries: List<HistoryEntry>) : Undoable
    data class HistoryRemoved(val entry: HistoryEntry) : Undoable
    data class Unstarred(val item: StarredItem) : Undoable

    /** The page skipped [videoId]'s [category] segment starting at [start]; Undo seeks back to it. */
    data class SegmentSkipped(val videoId: String, val category: SegmentCategory, val start: Double) : Undoable

    data class Imported(val report: ImportReport) : UiEvent
    data class ImportFailed(val problem: BackupProblem) : UiEvent
    data object Exported : UiEvent
    data object ExportFailed : UiEvent

    /** Watch moved to a new identity. */
    data object NewIdentity : UiEvent
}
