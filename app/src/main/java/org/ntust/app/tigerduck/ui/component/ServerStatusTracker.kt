package org.ntust.app.tigerduck.ui.component

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ServerKind {
    MOODLE,
    COURSE_SELECTION,
    BACKEND,
}

enum class ServerStatus {
    UNKNOWN,
    OK,
    FAILED,
}

object ServerStatusTracker {
    private val _statuses = MutableStateFlow<Map<ServerKind, ServerStatus>>(emptyMap())
    val statuses: StateFlow<Map<ServerKind, ServerStatus>> = _statuses.asStateFlow()

    /**
     * Whether cloud sync is switched on.
     *
     * The status map already reports BACKEND as UNKNOWN while sync is off,
     * which is the right colour but the wrong word: the header's detail list
     * would say "unknown" about a server the user deliberately turned off.
     * Kept here rather than plumbed through five ViewModels because this
     * object is already what the headers read, and the two writers that set
     * BACKEND -> UNKNOWN are the same ones that flip this.
     */
    private val _cloudSyncEnabled = MutableStateFlow(true)
    val cloudSyncEnabled: StateFlow<Boolean> = _cloudSyncEnabled.asStateFlow()

    fun setCloudSyncEnabled(enabled: Boolean) {
        _cloudSyncEnabled.value = enabled
    }

    fun set(status: ServerStatus, server: ServerKind) {
        _statuses.update { it + (server to status) }
    }

    fun status(server: ServerKind): ServerStatus =
        _statuses.value[server] ?: ServerStatus.UNKNOWN

    fun reset() {
        _statuses.value = emptyMap()
    }
}
