package com.staninna.yourtube

import com.staninna.yourtube.data.BackupProblem
import com.staninna.yourtube.data.HistoryEntry
import com.staninna.yourtube.data.ImportReport
import com.staninna.yourtube.data.StarredItem

/** Something the user should hear about once, in the snackbar. */
sealed interface UiEvent {
    /** A change the snackbar offers to take back; [MainViewModel.undo] does. */
    sealed interface Undoable : UiEvent

    data class HistoryCleared(val entries: List<HistoryEntry>) : Undoable
    data class HistoryRemoved(val entry: HistoryEntry) : Undoable
    data class Unstarred(val item: StarredItem) : Undoable

    data class Imported(val report: ImportReport) : UiEvent
    data class ImportFailed(val problem: BackupProblem) : UiEvent
    data object Exported : UiEvent
    data object ExportFailed : UiEvent
}
