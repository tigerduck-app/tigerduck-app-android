// Whose class table the page shows: the user's own, or someone else's imported from a share
// code, which is read-only and shows only the terms they shared.

package org.ntust.app.tigerduck.ui.screen.classtable

import org.ntust.app.tigerduck.data.sharing.ImportedClassTables
import org.ntust.app.tigerduck.data.sharing.ImportedPerson
import org.ntust.app.tigerduck.shared.Course

/** What the class table page draws. */
data class ShownClassTable(
    /** Null for the user's own class table. */
    val person: ImportedPerson?,
    val semester: String,
    /** The term picker's terms, newest first: the user's, or only the ones the person shared. */
    val semesters: List<String>,
    val courses: List<Course>,
    /** Counts the switches between class tables, for the page to keep its rows in place on one. */
    val switches: Int,
) {
    val isSomeoneElses: Boolean get() = person != null

    /**
     * Whether the page says the term has no courses. Someone else's says so whenever it is empty.
     * The user's own only once its first load has [settled] and while no fetch [isLoading]:
     * before, the list is one not read yet, and the state would flash ahead of the courses.
     */
    fun showsNoCourses(settled: Boolean, isLoading: Boolean): Boolean =
        courses.isEmpty() && (person != null || (settled && !isLoading))
}

/** Whose class table the page shows and on which term, which a switch sets together. */
internal data class DisplayedTable(val personId: String?, val semester: String?, val switches: Int)

/** Someone else's class table on [displayed], or the user's own [courses] on [semester]. */
internal fun shownTableOf(
    courses: List<Course>,
    semester: String,
    semesters: List<String>,
    tables: ImportedClassTables,
    displayed: DisplayedTable,
): ShownClassTable {
    val person = displayed.personId?.let { id -> tables.people.firstOrNull { it.id == id } }
        ?: return ShownClassTable(null, semester, semesters, courses, displayed.switches)
    // A new import of the person may have dropped the term on screen.
    val term = displayed.semester?.takeIf { it in person.semesters } ?: person.semester(keeping = semester)
    return ShownClassTable(person, term, person.semesters, person.courses(term), displayed.switches)
}

/**
 * Where the page goes when the store shows [tables]' person: someone else's class table keeps the
 * term on screen, [shownSemester], when they shared it and otherwise opens on their latest term;
 * the user's own comes back on its own term. Null when the page shows that class table already.
 */
internal fun nextDisplayedTable(
    displayed: DisplayedTable,
    tables: ImportedClassTables,
    shownSemester: String,
): DisplayedTable? {
    val person = tables.shownPerson
    if (person?.id == displayed.personId) return null
    return DisplayedTable(person?.id, person?.semester(keeping = shownSemester), displayed.switches + 1)
}

/**
 * Where the grid's period rows start, by period id, in the page's scrolled content, after
 * [switches] switches between class tables.
 */
data class ClassTableRowTops(val switches: Int, val tops: Map<String, Float>) {
    companion object {
        /**
         * How far a switch moved the first row at or below [visibleTop], the top of the page's
         * window onto its content, among the rows both class tables have; the last of them when
         * all are above it. Scrolling that far puts the rows back where they were on screen.
         */
        fun shift(before: ClassTableRowTops, after: ClassTableRowTops, visibleTop: Float): Float {
            val moves = before.tops.mapNotNull { (id, top) -> after.tops[id]?.let { top to it } }
            val row = moves.filter { it.first >= visibleTop }.minByOrNull { it.first }
                ?: moves.maxByOrNull { it.first }
            return row?.let { it.second - it.first } ?: 0f
        }
    }
}

/** A lookup or a save for someone else's class table that did not go through. */
enum class ImportedTableProblem { LOOKUP_FAILED, SAVE_FAILED }

/**
 * The Class Table tab's double taps: one more than [WINDOW_MS] after the last goes back to the
 * class table shown before, and quicker ones step through the list.
 */
internal class ClassTableTabDoubleTaps(private val now: () -> Long) {
    private var last: Long? = null

    /** Whether this double tap steps on to the next class table; it counts as the last one from now. */
    fun stepsOn(): Boolean {
        val time = now()
        val stepsOn = last?.let { time - it <= WINDOW_MS } == true
        last = time
        return stepsOn
    }

    companion object {
        const val WINDOW_MS = 3_000L
    }
}
