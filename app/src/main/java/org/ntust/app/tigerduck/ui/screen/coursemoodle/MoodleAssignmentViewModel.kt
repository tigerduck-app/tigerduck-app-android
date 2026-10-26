package org.ntust.app.tigerduck.ui.screen.coursemoodle

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.moodle.MoodleAccountAssignments
import org.ntust.app.tigerduck.data.moodle.MoodleAssignmentEntry
import org.ntust.app.tigerduck.data.moodle.MoodleAssignmentLookup
import org.ntust.app.tigerduck.data.moodle.MoodleCourseRepository
import org.ntust.app.tigerduck.data.moodle.MoodleCourseStore
import javax.inject.Inject

/**
 * Hands an assignment's page the account's assignments, with what the cache has of the one in
 * its route, and the store of its course, which its files download through. The page is reached
 * from Home, a reminder and the Live Update, outside any course detail, so the course comes from
 * the assignment's record rather than from a route.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MoodleAssignmentViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MoodleCourseRepository,
    authService: AuthService,
) : ViewModel() {
    val assignmentId: String = savedStateHandle.get<String>(MoodleRoutes.ARG_ASSIGNMENT_ID).orEmpty()

    /** False after a sign-out, when the page holds the assignments of the account that left. */
    val signedIn: StateFlow<Boolean> = authService.authState

    private val _assignments = MutableStateFlow<MoodleAccountAssignments?>(null)
    val assignments: StateFlow<MoodleAccountAssignments?> = _assignments.asStateFlow()

    /** What the cache has of the assignment, as it changes under the page. */
    val lookup: StateFlow<MoodleAssignmentLookup> = _assignments
        .flatMapLatest { it?.lookup(assignmentId) ?: flowOf(MoodleAssignmentLookup.Loading) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MoodleAssignmentLookup.Loading)

    /** The store of the assignment's course, once its record names one; its files open through it. */
    val store: StateFlow<MoodleCourseStore?> = lookup
        .map { (it as? MoodleAssignmentLookup.Found)?.entry?.record?.courseId?.takeIf { id -> id > 0 } }
        .distinctUntilChanged()
        .flatMapLatest { courseId -> flow { emit(courseId?.let { repository.store(it) }) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { _assignments.value = repository.assignments() }
    }

    /** Whether the page handed off to Moodle to submit since it last came back; see [noteMoodleHandOff]. */
    private var handedOffToMoodle = false

    /**
     * Fetches the submission again: at once with [force], as Retry asks, else only once the copy
     * is old enough; see [MoodleAccountAssignments.refreshSubmission].
     */
    fun refresh(entry: MoodleAssignmentEntry, force: Boolean = false) {
        viewModelScope.launch { _assignments.value?.refreshSubmission(entry, force) }
    }

    /**
     * The page is opening the assignment on Moodle to submit, so the next return to the app
     * fetches the submission whatever the copy's age: it may just have been made.
     */
    fun noteMoodleHandOff() {
        handedOffToMoodle = true
    }

    /**
     * What the page does as it opens and on each return to the app. A hand-off is spent only by
     * the request it forces, which goes out on its own after any still running, so a return before
     * the account's assignments are in hand keeps it for the next.
     */
    fun refreshOnResume(entry: MoodleAssignmentEntry) {
        viewModelScope.launch {
            val assignments = _assignments.value ?: return@launch
            val force = handedOffToMoodle
            if (force) handedOffToMoodle = false
            assignments.refreshSubmission(entry, force)
        }
    }
}
