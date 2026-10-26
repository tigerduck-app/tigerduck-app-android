package org.ntust.app.tigerduck.ui.screen.home

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A user edit on any screen that goes up to the backend: a course renamed,
 * recoloured, ignored, added, deleted or reset on the class table. Home's
 * revision poll ([RevisionPoller.noteActivity]) runs at its active cadence
 * after each, so another device's follow-up arrives quickly, as it does after
 * a mark tapped on Home. The iPhone app's poller hears of the same edits
 * (`AppState.syncCourseOverride`, `syncCourseIgnore`, `deleteBackendCourse`).
 */
@Singleton
class LocalEdits @Inject constructor() {
    private val _edits = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val edits: SharedFlow<Unit> = _edits.asSharedFlow()

    fun noteEdit() {
        _edits.tryEmit(Unit)
    }
}
