// The GE dimension, the course's term span and whether it is required: read
// off QueryCourse, kept on the course, and shown as rows in the detail dialog.
// Mirrors iOS 69c530ad and tigerduck-app#208.

package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.annotation.StringRes
import org.ntust.app.tigerduck.R

internal object CourseDetailMetadata {

    /**
     * The dimension row's value, or null to leave the row out. Only
     * general-education courses carry one; every other course reports an
     * empty string, and the row would be a label with nothing beside it.
     */
    fun dimension(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The duration row's label. QueryCourse spells `AllYear` "F" (spans the
     * academic year) or "H" (a single semester). Anything else, including a
     * row cached before the field existed, says nothing, so the row is left
     * out rather than shown blank.
     */
    @StringRes
    fun durationLabel(allYear: String?): Int? = when (allYear?.trim()?.uppercase()) {
        "F" -> R.string.course_detail_duration_full_year
        "H" -> R.string.course_detail_duration_one_semester
        else -> null
    }

    /**
     * The course type row's label. QueryCourse spells `RequireOption` "R"
     * (required) or "E" (elective). Anything else, such as the null of a row
     * merged from another device or cached before the field existed, says
     * nothing, so the row is left out rather than shown blank.
     */
    @StringRes
    fun requireOptionLabel(requireOption: String?): Int? = when (requireOption?.trim()?.uppercase()) {
        "R" -> R.string.course_detail_require_option_required
        "E" -> R.string.course_detail_require_option_elective
        else -> null
    }
}
