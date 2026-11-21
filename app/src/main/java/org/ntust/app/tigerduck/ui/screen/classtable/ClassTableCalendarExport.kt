package org.ntust.app.tigerduck.ui.screen.classtable

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.shared.clock.AppClock
import java.io.File
import java.time.Instant
import javax.inject.Inject

/**
 * The ⋮ menu's Export ICS: the shown term as an ICS file, handed to the share sheet as the
 * screenshot is, for the user's own table and someone else's. Ported from iOS
 * `ClassTableHeaderMenu.exportCalendar`.
 */
@HiltViewModel
class ClassTableCalendarExportViewModel @Inject constructor(
    private val calendar: AcademicCalendarStore,
) : ViewModel() {

    /** The term of the table [shown] draws, with its holidays; null when the academic calendar lacks it. */
    fun term(shown: ShownClassTable): ClassTableCalendarFile.Term? = ClassTableCalendarFile.term(
        shown.semester, ownTable = shown.person == null, calendar.current(), calendar.optedInHolidayIds,
    )

    /** Whether the item acts: the table has a class at a period time and the term has dates. */
    fun canExport(shown: ShownClassTable): Boolean =
        ClassTableCalendarFile.hasTimedMeeting(shown.courses) && term(shown) != null

    /** Whether a file is being written, which holds both exports off. */
    var isExporting by mutableStateOf(false)
        private set

    /** [share] on [scope], telling [onFailed] when it did not go through. */
    fun shareOn(
        scope: CoroutineScope,
        context: Context,
        shown: ShownClassTable,
        termLabel: (String) -> String,
        onFailed: suspend () -> Unit,
    ) {
        scope.launch { if (!share(context, shown, termLabel)) onFailed() }
    }

    /**
     * Writes the file, named with the term as [termLabel] writes it, and hands it to the share
     * sheet; false when the term has no dates, no class falls in it, or the file could not be
     * written or shared.
     */
    suspend fun share(context: Context, shown: ShownClassTable, termLabel: (String) -> String): Boolean {
        isExporting = true
        return try {
            val label = termLabel(shown.semester)
            val file = term(shown)?.let { writeClassTableCalendar(context, shown, it, label, AppClock.instant()) }
            if (file != null) shareClassTableExport(context, file, context.getString(R.string.class_table_export_ics), ICS_TYPE)
            file != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        } finally {
            isExporting = false
        }
    }
}

/**
 * Writes the term of the table [shown] draws as an ICS file named as the screenshot is, with the
 * nickname of someone else's table; null, and nothing written, when no class of it falls in [term].
 */
internal suspend fun writeClassTableCalendar(
    context: Context,
    shown: ShownClassTable,
    term: ClassTableCalendarFile.Term,
    semesterLabel: String,
    now: Instant,
): File? {
    val events = ClassTableCalendarFile.events(shown, term)
    if (events.isEmpty()) return null
    val text = ClassTableCalendarFile.text(events, term, now)
    val name = classTableExportFileName(context.getString(R.string.feature_class_table), semesterLabel, shown.person?.nickname, "ics")
    return writeClassTableExport(context, name) { it.write(text.toByteArray(Charsets.UTF_8)) }
}

private const val ICS_TYPE = "text/calendar"
