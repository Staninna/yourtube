package com.staninna.yourtube

import com.staninna.yourtube.data.BulkTagging
import com.staninna.yourtube.data.LibraryView
import com.staninna.yourtube.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Library's selection mode: rows picked by long press and taps, and what can be done to all of them
 * at once (tag, remove). Only rows Library shows stay selected: a filter, a search or a removal that
 * hides a row drops it for good, so it does not come back selected with the row, and with none left
 * selection mode ends. The bulk tag sheet works on the rows selected when it opened, so a tap that
 * moves them out of the filter does not close it. Runs on [scope], one coroutine at a time.
 */
class LibrarySelection(
    private val store: Store,
    scope: CoroutineScope,
    private val shown: StateFlow<LibraryView>,
    /** Opens the single-item tag sheet, which Tag uses when one row is selected. */
    private val openTagSheet: (String) -> Unit,
    private val emit: (UiEvent) -> Unit,
) {
    private val picked = MutableStateFlow<Set<String>>(emptySet())

    /** The rows the open bulk tag sheet edits; null while it is closed. */
    private val bulkIds = MutableStateFlow<Set<String>?>(null)

    /** The selected rows, in Library's order; empty means selection mode is off. */
    val selected: StateFlow<Set<String>> = combine(picked, shown, ::visible)
        .stateIn(scope, SharingStarted.Eagerly, visible(picked.value, shown.value))

    /** The open bulk tag sheet; it closes by itself once none of its rows is starred. */
    val bulkTags: StateFlow<BulkTagging?> = combine(bulkIds, store.library) { ids, lib -> ids?.let { BulkTagging.of(lib, it) } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        // A row that leaves the screen leaves the selection, not just the view of it.
        scope.launch { shown.collect { view -> picked.update { visible(it, view) } } }
    }

    /** A tap in selection mode, or the long press that starts it: [itemId] in or out. */
    fun toggle(itemId: String) = picked.update { visible(it, shown.value).let { now -> if (itemId in now) now - itemId else now + itemId } }

    fun selectAll() {
        picked.value = shown.value.items.mapTo(LinkedHashSet()) { it.id }
    }

    /** Ends selection mode, and the bulk tag sheet with it. */
    fun clear() {
        picked.value = emptySet()
        bulkIds.value = null
    }

    /** Tag: one row opens its own tag sheet (with its channel switch), several the bulk sheet. */
    fun tag() {
        val ids = selected.value
        if (ids.size == 1) openTagSheet(ids.single()) else if (ids.isNotEmpty()) bulkIds.value = ids
    }

    fun closeBulkTags() {
        bulkIds.value = null
    }

    /** Puts [tagId] on every row of the bulk sheet, or takes it off every one. */
    fun setTag(tagId: String, attached: Boolean) {
        store.setTag(bulkIds.value ?: return, tagId, attached)
    }

    /** Creates a tag (or reuses one with that name) and attaches it to every row of the bulk sheet. */
    fun createTag(name: String) {
        store.createTag(name, attachTo = bulkIds.value ?: return)
    }

    /** Takes every selected row out of the library with one Undo, and ends selection mode. */
    fun remove() {
        val ids = selected.value
        val items = store.library.value.items.filter { it.id in ids }
        clear()
        if (items.isEmpty()) return
        store.unstar(ids)
        emit(UiEvent.Unstarred(items))
    }

    private companion object {
        /** [ids] limited to the rows [view] shows, in their order. */
        fun visible(ids: Set<String>, view: LibraryView): Set<String> =
            if (ids.isEmpty()) ids else view.items.mapNotNullTo(LinkedHashSet()) { it.id.takeIf(ids::contains) }
    }
}
