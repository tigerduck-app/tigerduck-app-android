package org.ntust.app.tigerduck.ui.screen.classtable

import android.icu.text.ListFormatter
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.util.formatCredits

// The iPhone's system orange, as the app's other warnings use it.
private val ConflictOrange = Color(0xFFFF9500)

/**
 * What a result row offers, and its mark; see [addCourseRowState]. TalkBack reads the mark as the
 * row's state and hint, and never the icon itself. As the iPhone's `AddCourseRowMark`.
 */
internal enum class AddCourseRowState(
    val icon: ImageVector,
    @param:StringRes val value: Int?,
    @param:StringRes val hint: Int?,
) {
    /** Not on the class table: a tap adds it. */
    Addable(Icons.Outlined.AddCircleOutline, value = null, hint = R.string.action_add),

    /** Added while this sheet has been open: an accent-filled check a tap takes back out. */
    AddedThisSession(Icons.Filled.CheckCircle, value = R.string.add_course_added, hint = R.string.action_remove),

    /** On the class table before the sheet opened: a gray check, left alone and read as disabled. */
    AlreadyThere(Icons.Filled.Check, value = R.string.add_course_added, hint = null),
}

/**
 * The state of the row for [courseNo], given the class table's courses and the ones added in this
 * session. The session wins: an added course is on the class table too by the time the row is
 * drawn again. As on the iPhone (tigerduck-app#84).
 */
internal fun addCourseRowState(
    courseNo: String,
    existingCourseNos: Set<String>,
    sessionAddedCourseNos: Set<String>,
): AddCourseRowState = when (courseNo) {
    in sessionAddedCourseNos -> AddCourseRowState.AddedThisSession
    in existingCourseNos -> AddCourseRowState.AlreadyThere
    else -> AddCourseRowState.Addable
}

/** One result: the course, its meta lines and clashes, and its mark at the end. */
@Composable
internal fun AddCourseResultRow(
    shown: AddCourseShown,
    names: AddCourseNames,
    state: AddCourseRowState,
    onTap: () -> Unit,
) {
    val course = shown.course
    val secondary = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
    val value = state.value?.let { stringResource(it) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Courses the user already had are not removable here: tap-to-remove would be a
            // dangerous escape hatch. Only courses added in this session toggle off.
            .clickable(
                enabled = state != AddCourseRowState.AlreadyThere,
                onClickLabel = state.hint?.let { stringResource(it) },
                onClick = onTap,
            )
            .semantics { if (value != null) stateDescription = value }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(names.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            Text(
                stringResource(R.string.add_course_result_meta, course.courseNo, names.instructor, course.credits.formatCredits()),
                style = MaterialTheme.typography.labelSmall,
                color = secondary,
            )
            if (course.classroom.isNotEmpty()) {
                Text(course.classroom, style = MaterialTheme.typography.labelSmall, color = secondary)
            }
            if (course.nodeDisplay.isNotEmpty()) {
                Text(course.nodeDisplay, style = MaterialTheme.typography.labelSmall, color = secondary)
            }
            if (state == AddCourseRowState.Addable && shown.conflicts.isNotEmpty()) {
                ConflictLine(shown.conflicts)
            }
        }
        Icon(
            state.icon,
            contentDescription = null,
            tint = if (state == AddCourseRowState.AlreadyThere) secondary else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun ConflictLine(conflicts: List<String>) {
    val locale = LocalConfiguration.current.locales[0]
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = ConflictOrange, modifier = Modifier.size(14.dp))
        Text(
            stringResource(R.string.course_search_conflicts_with, ListFormatter.getInstance(locale).format(conflicts)),
            style = MaterialTheme.typography.labelSmall,
            color = ConflictOrange,
        )
    }
}
