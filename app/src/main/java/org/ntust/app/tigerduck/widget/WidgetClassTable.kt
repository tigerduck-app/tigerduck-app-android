package org.ntust.app.tigerduck.widget

import android.content.ComponentName
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.stringPreferencesKey
import org.ntust.app.tigerduck.academic.QuietDays
import org.ntust.app.tigerduck.data.sharing.ImportedPerson
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.theme.buildCourseColorAssignments
import org.ntust.app.tigerduck.widget.receivers.TodayDarkWidgetReceiver
import org.ntust.app.tigerduck.widget.receivers.TodayLightWidgetReceiver
import org.ntust.app.tigerduck.widget.receivers.WeekDarkWidgetReceiver
import org.ntust.app.tigerduck.widget.receivers.WeekLightWidgetReceiver

/**
 * Whose class table a Today or Week widget shows: the user's own, or one imported from a share
 * code, chosen in the widget's configuration ([WidgetClassTableActivity]), as the iPhone's widget
 * editor chooses it (`ChooseClassTableIntent`). The Next Class widget always shows the user's own.
 */
internal object WidgetClassTable {
    /**
     * The id of the imported person a widget shows, kept in the widget's own Glance state, so per
     * appWidgetId: Glance deletes a widget's state in its receiver's `onDeleted`. Absent for the
     * user's own, as on every widget placed before the choice.
     */
    val PersonKey = stringPreferencesKey("widget_class_table_person")

    /** The receivers of the widgets that take a choice: Today and Week, and their hidden twins. */
    val receivers: Set<String> = setOf(
        TodayLightWidgetReceiver::class.java.name,
        TodayDarkWidgetReceiver::class.java.name,
        WeekLightWidgetReceiver::class.java.name,
        WeekDarkWidgetReceiver::class.java.name,
    )

    /** Whether a widget of [provider] takes a choice: one of [receivers], in [packageName], this app. */
    fun takesChoice(provider: ComponentName?, packageName: String): Boolean =
        provider != null && provider.packageName == packageName && provider.className in receivers

    /** The person [personId] names among [people]; null for the user's own, chosen so or since deleted. */
    fun personOf(personId: String?, people: List<ImportedPerson>): ImportedPerson? =
        personId?.let { id -> people.firstOrNull { it.id == id } }
}

/** The class table a widget draws, and what its classes are drawn with. */
internal data class WidgetTable(
    val courses: List<Course>,
    val quietDays: QuietDays,
    val courseColors: Map<String, Color>,
    val isLoggedIn: Boolean,
)

/**
 * The class table a widget set to [person] draws on [semester], the current term: the user's
 * [own] courses with their [quietDays] while [person] is null.
 *
 * Someone else's, as the class table page shows it and the iPhone's widgets draw it: their
 * courses under the school's names, in the app's course colors (the user's own color for a course
 * both take, else the palette's), every holiday quiet whichever ones the user kept classes on, and
 * no sign-in in front of it, as the table is kept on the device.
 */
internal fun widgetTable(
    person: ImportedPerson?,
    semester: String,
    own: List<Course>,
    isLoggedIn: Boolean,
    quietDays: QuietDays,
): WidgetTable {
    val ownColors = buildCourseColorAssignments(own)
    if (person == null) return WidgetTable(own, quietDays, ownColors, isLoggedIn)
    val courses = person.courses(semester).map { it.copy(customCourseName = null, customColorHex = null) }
    val courseNos = courses.mapTo(mutableSetOf()) { it.courseNo }
    return WidgetTable(
        courses = courses,
        quietDays = quietDays.copy(optedInHolidayIds = emptySet()),
        courseColors = ownColors.filterKeys { it in courseNos },
        isLoggedIn = true,
    )
}
