package org.ntust.app.tigerduck.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.data.storage.AppStorage
import org.ntust.app.tigerduck.di.ApplicationScope
import javax.inject.Inject

/**
 * Settings' Storage page: what each kind takes, measured each time the page shows, and clearing a
 * kind or every downloaded file. The sizes and the turns are [AppStorage]'s, shared with every
 * page. A clearing runs in the app's scope: once asked for, it finishes even if the page closes
 * first, and the page open then shows it.
 */
@HiltViewModel
class StorageViewModel @Inject constructor(
    private val storage: AppStorage,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {
    /** Null until the first measure ends, so the rows say "…" rather than sizes not measured. */
    val sizes: StateFlow<Map<AppStorage.Kind, Long>?> = storage.sizes

    fun measure() {
        viewModelScope.launch { storage.refresh() }
    }

    fun clear(kind: AppStorage.Kind) {
        appScope.launch { storage.clear(kind) }
    }

    fun clearDownloaded() {
        appScope.launch { storage.clearDownloaded() }
    }
}
