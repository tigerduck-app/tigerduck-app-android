package org.ntust.app.tigerduck.ui.screen.coursemoodle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.moodle.MoodleCommonTerm
import org.ntust.app.tigerduck.data.moodle.MoodleCourseStore
import org.ntust.app.tigerduck.shared.time.AcademicTerm
import org.ntust.app.tigerduck.ui.component.CourseListRow
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

/**
 * Member [memberId]'s courses in common with the account, asked of Moodle when the page opens and
 * again while it shows once the kept answer is older than its interval (#701). Asked again once
 * the members list names the account ([ownId]), which it may do after the page opens.
 */
@Composable
internal fun rememberCommonCourses(store: MoodleCourseStore?, memberId: Int, ownId: Int?): List<MoodleCommonTerm> {
    val courses = store?.memberCourses ?: return emptyList()
    val feed = remember(courses, memberId) { courses.feed(memberId) }
    MoodleAutoRefresh(listOf(courses, memberId, ownId), feed.ttlMs, feed::awaitCache, { feed.state.value.fetchedAtMs }) {
        courses.refresh(memberId)
    }
    val terms by remember(courses, memberId) { courses.terms(memberId) }.collectAsStateWithLifecycle(emptyList())
    return terms
}

/**
 * The courses in common on a member's page, by term, newest first, those of no term under
 * Other; the rows do nothing on a tap. As the iPhone's (tigerduck-app#984).
 */
@Composable
internal fun MemberCommonCourses(terms: List<MoodleCommonTerm>) {
    val other = stringResource(R.string.course_member_common_courses_other)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.course_member_common_courses),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        terms.forEach { term ->
            Text(
                text = term.term?.let(AcademicTerm::labelOf) ?: other,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp).semantics { heading() },
            )
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                Column {
                    term.courses.forEachIndexed { index, course ->
                        if (index > 0) HorizontalDivider(modifier = Modifier.padding(start = 44.dp), color = cardDividerColor())
                        CourseListRow(course.colorKey, course.name, course.details)
                    }
                }
            }
        }
    }
}
