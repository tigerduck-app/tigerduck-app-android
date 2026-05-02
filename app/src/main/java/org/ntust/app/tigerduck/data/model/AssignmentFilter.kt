package org.ntust.app.tigerduck.data.model

import androidx.annotation.StringRes
import org.ntust.app.tigerduck.R

enum class AssignmentFilter(@param:StringRes val displayNameRes: Int) {
    INCOMPLETE(R.string.assignment_filter_incomplete),
    ALL(R.string.assignment_filter_all),
    IGNORED(R.string.assignment_filter_ignored)
}
