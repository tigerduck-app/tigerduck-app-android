package org.ntust.app.tigerduck.ui.screen.classtable

import org.ntust.app.tigerduck.coursecatalog.CourseCatalog
import org.ntust.app.tigerduck.coursecatalog.CourseCatalogStore
import org.ntust.app.tigerduck.coursecatalog.CourseSearchOptions
import org.ntust.app.tigerduck.network.model.CourseSearchResult
import org.ntust.app.tigerduck.shared.Periods
import org.ntust.app.tigerduck.util.HanScript
import java.util.Locale

/** A class period on one weekday; [day] counts from 1 for Monday, as `parseNodeToSchedule` does. */
data class CourseSlot(val day: Int, val period: String)

/** A class table course, as the search compares results with it. */
data class CourseSearchTableCourse(val courseNo: String, val name: String, val slots: Set<CourseSlot>)

/** The Add course filters. They last for one sheet, so nothing here is stored. */
data class CourseSearchFilters(
    val hideConflicts: Boolean = false,
    val department: String? = null,
    val level: Level = Level.All,
    val dimension: String? = null,
    val periods: Set<CourseSlot> = emptySet(),
    val generalOnly: Boolean = false,
    val englishTaught: Boolean = false,
    val intensiveOnly: Boolean = false,
    val excludeAlliance: Boolean = false,
) {
    enum class Level { All, Undergraduate, Graduate }

    val isDefault: Boolean get() = this == CourseSearchFilters()

    /**
     * The filters the catalog search applies. The others only narrow its results on the sheet,
     * so a change to them runs no search.
     */
    val searched: CourseSearchFilters
        get() = copy(hideConflicts = CourseSearchFilters().hideConflicts, periods = emptySet())
}

/** The Add course search over the term's catalogs, on the device; as the iPhone's `CourseSearch`. */
object CourseSearch {
    val gridDays = (1..6).toList()

    private val courseNoPattern = Regex("^[A-Za-z0-9]+$")

    // A course number's third character, as the school's level filters read it.
    private val undergraduateLevels = setOf('1', '2', '3', '4', 'G')
    private val graduateLevels = setOf('5', '6', '7', '8', '9')

    /**
     * Each language's rows that pass the filters and match, in catalog order; a language that
     * found nothing has no entry. A query of letters and digits is first a course-number prefix,
     * and the name and teacher are matched only when no course number starts with it.
     */
    fun search(
        query: String,
        catalogs: Map<String, List<CourseCatalog.Course>>,
        filters: CourseSearchFilters,
    ): Map<String, List<CourseSearchResult>> {
        if (isCourseNumber(query)) {
            val prefix = query.uppercase(Locale.ROOT)
            val found = rows(catalogs, filters) { _, course -> course.numberKey.startsWith(prefix) }
            if (found.isNotEmpty()) return found
        }
        // The zh names are in Traditional characters, so Simplified input is converted.
        val zhNeedle = (if (HanScript.containsHan(query)) HanScript.toTraditional(query) else query).lowercase(Locale.ROOT)
        val otherNeedle = query.lowercase(Locale.ROOT)
        return rows(catalogs, filters) { language, course ->
            course.textKey.contains(if (language == "zh") zhNeedle else otherNeedle)
        }
    }

    /** Whether [results] are [query]'s course-number matches rather than its name and teacher matches. */
    fun foundByNumber(query: String, results: Map<String, List<CourseSearchResult>>): Boolean {
        if (!isCourseNumber(query)) return false
        val first = results.values.firstOrNull { it.isNotEmpty() }?.first() ?: return false
        return first.courseNo.uppercase(Locale.ROOT).startsWith(query.uppercase(Locale.ROOT))
    }

    private fun isCourseNumber(query: String) = courseNoPattern.matches(query)

    private inline fun rows(
        catalogs: Map<String, List<CourseCatalog.Course>>,
        filters: CourseSearchFilters,
        matches: (language: String, CourseCatalog.Course) -> Boolean,
    ): Map<String, List<CourseSearchResult>> {
        val found = LinkedHashMap<String, List<CourseSearchResult>>()
        for ((language, courses) in catalogs) {
            val rows = courses.mapNotNull { if (admits(it, filters) && matches(language, it)) it.row else null }
            if (rows.isNotEmpty()) found[language] = rows
        }
        return found
    }

    fun admits(course: CourseCatalog.Course, filters: CourseSearchFilters): Boolean {
        val row = course.row
        val level = course.numberKey.getOrNull(2) ?: ' '
        val levelMatches = when (filters.level) {
            CourseSearchFilters.Level.All -> true
            CourseSearchFilters.Level.Undergraduate -> level in undergraduateLevels
            CourseSearchFilters.Level.Graduate -> level in graduateLevels
        }
        return levelMatches &&
            (filters.department?.let { course.numberKey.startsWith(it.uppercase(Locale.ROOT)) } ?: true) &&
            (filters.dimension?.let { row.dimension == it } ?: true) &&
            (!filters.generalOnly || !row.dimension.isNullOrEmpty()) &&
            (!filters.englishTaught || course.isEnglishTaught) &&
            (!filters.intensiveOnly || course.isIntensive) &&
            (!filters.excludeAlliance || !course.isAlliance)
    }

    /** The rows to show, and each course's name in the other language where that language found it. */
    data class Shown(val rows: List<CourseSearchResult>, val otherNames: Map<String, String>)

    /** The primary language's rows, then the courses only another language found. */
    fun shown(results: Map<String, List<CourseSearchResult>>, primary: String): Shown {
        val mine = results[primary].orEmpty()
        val other = CourseCatalogStore.LANGUAGES.filter { it != primary }.flatMap { results[it].orEmpty() }
        val mineNos = mine.mapTo(HashSet()) { it.courseNo }
        val otherNames = LinkedHashMap<String, String>().apply { other.forEach { putIfAbsent(it.courseNo, it.courseName) } }
        return Shown(mine + other.filter { it.courseNo !in mineNos }, otherNames)
    }

    fun slots(schedule: Map<Int, List<String>>): Set<CourseSlot> =
        schedule.flatMap { (day, periods) -> periods.map { CourseSlot(day, it) } }.toSet()

    /**
     * The names of the class table courses a result clashes with, or null when the sheet's own
     * filters hide it. A course already in the table clashes with nothing. With periods picked, a
     * course must meet in them only, so one without class times is hidden.
     */
    fun visibleConflicts(
        courseNo: String,
        schedule: Map<Int, List<String>>,
        table: List<CourseSearchTableCourse>,
        filters: CourseSearchFilters,
    ): List<String>? {
        val slots = slots(schedule)
        if (filters.periods.isNotEmpty() && (slots.isEmpty() || !filters.periods.containsAll(slots))) return null
        if (table.any { it.courseNo == courseNo }) return emptyList()
        val names = table.filter { course -> course.slots.any { it in slots } }.map { it.name }
        return if (filters.hideConflicts && names.isNotEmpty()) null else names
    }

    fun freeSlots(busy: Set<CourseSlot>): Set<CourseSlot> =
        gridDays.flatMap { day -> Periods.chronologicalOrder.map { CourseSlot(day, it) } }.toSet() - busy

    /** The colleges with only the departments whose code or name holds [query], ignoring case. */
    fun colleges(colleges: List<CourseSearchOptions.College>, query: String): List<CourseSearchOptions.College> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return colleges
        return colleges.mapNotNull { college ->
            val departments = college.departments.filter { department ->
                listOf(department.code, department.name, department.englishName).any { it.contains(trimmed, ignoreCase = true) }
            }
            if (departments.isEmpty()) null else college.copy(departments = departments)
        }
    }
}
