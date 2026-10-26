package org.ntust.app.tigerduck.ui.screen.coursemoodle

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.moodle.MoodleAccountAssignments
import org.ntust.app.tigerduck.data.moodle.MoodleCourseRepository
import org.ntust.app.tigerduck.data.moodle.MoodleCourseStore
import org.ntust.app.tigerduck.mail.MailAccount
import javax.inject.Inject

/**
 * Hands a Moodle screen its course's [MoodleCourseStore]: by the `courseId` in its route, or, on
 * the course detail, by the course it shows, once the course's Moodle id is known.
 */
@HiltViewModel
class MoodleCourseViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MoodleCourseRepository,
    authService: AuthService,
    mailAccount: MailAccount,
) : ViewModel() {
    private val _store = MutableStateFlow<MoodleCourseStore?>(null)
    val store: StateFlow<MoodleCourseStore?> = _store.asStateFlow()

    /** False after a sign-out, when a pushed screen holds the store of the account that left. */
    val signedIn: StateFlow<Boolean> = authService.authState

    /** Whether a member's email opens the app's own School Mail compose rather than a mail app. */
    val schoolMailSignedIn: StateFlow<Boolean> = mailAccount.signedIn

    private val _assignments = MutableStateFlow<MoodleAccountAssignments?>(null)

    /** The account's assignments, which the course's list reads its rows from; null while signed out. */
    val assignments: StateFlow<MoodleAccountAssignments?> = _assignments.asStateFlow()

    private var bound: Pair<Int?, String?>? = null
    private var binding: Job? = null

    init {
        savedStateHandle.get<Int>(ARG_COURSE_ID)?.let { bind(it, null) }
        viewModelScope.launch { _assignments.value = repository.assignments() }
    }

    /** Builds or finds the store of Moodle course [courseId]; null leaves the screen without one. */
    fun bind(courseId: Int?, courseNo: String?) {
        if (bound == (courseId to courseNo)) return
        bound = courseId to courseNo
        binding?.cancel()
        if (courseId == null) {
            _store.value = null
            return
        }
        binding = viewModelScope.launch { _store.value = repository.store(courseId, courseNo) }
    }

    companion object {
        const val ARG_COURSE_ID = "courseId"
    }
}
