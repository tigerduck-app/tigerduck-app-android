package org.ntust.app.tigerduck.ui.screen.classtable.sharing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableChange
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
import org.ntust.app.tigerduck.data.sharing.ImportedClassTables
import org.ntust.app.tigerduck.data.sharing.ImportedPerson
import javax.inject.Inject

/**
 * The Class tables list: the imported people by placement, renamed, deleted and moved there as on
 * the class table page. Ported from iOS `ClassTableListView`.
 */
@HiltViewModel
class ClassTableListViewModel @Inject constructor(
    private val store: ImportedClassTableStore,
) : ViewModel() {

    val tables: StateFlow<ImportedClassTables> = store.tables

    private val _saveFailed = MutableStateFlow(false)

    /** A change the store could not write, which the page reports until dismissed. */
    val saveFailed: StateFlow<Boolean> = _saveFailed.asStateFlow()

    init {
        // The list may open before the class table page ever read the store.
        viewModelScope.launch { store.load() }
    }

    fun rename(id: String, nickname: String) = change { store.rename(id, nickname) }

    /** Deleting the shown person shows the user's own class table again, as on the class table page. */
    fun delete(id: String) = change { store.delete(id) }

    /** Moves [id] into [placement], before [before] there or last; see [ImportedClassTableStore.move]. */
    fun move(id: String, placement: ImportedPerson.Placement, before: String?) = change { store.move(id, placement, before) }

    fun dismissSaveFailed() {
        _saveFailed.value = false
    }

    private fun change(write: suspend () -> ImportedClassTableChange) {
        viewModelScope.launch {
            if (write() == ImportedClassTableChange.FAILED) _saveFailed.value = true
        }
    }
}
