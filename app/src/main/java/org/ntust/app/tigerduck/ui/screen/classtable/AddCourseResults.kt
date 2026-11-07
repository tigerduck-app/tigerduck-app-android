package org.ntust.app.tigerduck.ui.screen.classtable

import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.network.model.CourseSearchResult
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.HanScript
import org.ntust.app.tigerduck.util.toCreditsOrZero

/** One course of the results, its rows (one per room and day set) merged. */
internal data class AddCourseGroup(
    val courseNo: String,
    val courseName: String,
    val instructor: String,
    val credits: Float,
    val classroom: String,
    val enrolledCount: Int,
    val maxCount: Int,
    val schedule: Map<Int, List<String>>,
    /** "weekday-period" -> deduped room for that slot. See [Course.classroom]. */
    val classroomMap: Map<String, String>,
    val nodeDisplay: String,
    /**
     * Carried straight from the catalog so a course added by hand shows its dimension, term span
     * and course type without waiting for the next refresh.
     */
    val dimension: String?,
    val allYear: String?,
    val requireOption: String?,
) {
    /** The course to add, named [name] and taught by [instructor]: its row in the app's language. */
    fun tableCourse(name: String, instructor: String): Course = Course.fromSchedule(
        courseNo = courseNo,
        courseName = name,
        instructor = instructor,
        credits = credits,
        classroom = classroom,
        enrolledCount = enrolledCount,
        maxCount = maxCount,
        schedule = schedule,
        classroomMap = classroomMap,
        dimension = dimension,
        allYear = allYear,
        requireOption = requireOption,
    )
}

/** A result as the sheet lists it: its course, and the class table courses it clashes with. */
internal data class AddCourseShown(val course: AddCourseGroup, val conflicts: List<String>)

/** What a result row says: its course's name and teacher in the app's language, and the title shown. */
internal data class AddCourseNames(val name: String, val instructor: String, val title: String)

internal object AddCourseResults {

    /** The results the sheet's filters leave, in [CourseSearch.shown]'s order, each with its clashes. */
    fun shown(
        results: Map<String, List<CourseSearchResult>>,
        language: String,
        table: List<CourseSearchTableCourse>,
        filters: CourseSearchFilters,
        parseNode: (String?) -> Map<Int, List<String>>,
    ): List<AddCourseShown> = grouped(CourseSearch.shown(results, language).rows, parseNode).mapNotNull { course ->
        CourseSearch.visibleConflicts(course.courseNo, course.schedule, table, filters)?.let { AddCourseShown(course, it) }
    }

    /**
     * A row's names: [appRow], the course's row in the app's language, names it, else the row it
     * was found with. A query in the other language shows that language's name beside it, so a
     * user searching in a language they do not read the app in still knows the result; a course
     * number is both languages' and adds none.
     */
    fun names(
        course: AddCourseGroup,
        appRow: CourseSearchResult?,
        otherRow: CourseSearchResult?,
        query: String,
        foundByNumber: Boolean,
        language: String,
    ): AddCourseNames {
        val name = appRow?.courseName?.takeIf { it.isNotBlank() } ?: course.courseName
        val instructor = appRow?.courseTeacher?.takeIf { it.isNotBlank() } ?: course.instructor
        val queryLanguage = when {
            foundByNumber -> language
            HanScript.containsHan(query) -> "zh"
            else -> "en"
        }
        val other = otherRow?.courseName
        val title = if (queryLanguage != language && !other.isNullOrBlank() && other != name) "$name ($other)" else name
        return AddCourseNames(name, instructor, title)
    }

    /** [rows] merged by course number, in the order each course first comes. */
    fun grouped(rows: List<CourseSearchResult>, parseNode: (String?) -> Map<Int, List<String>>): List<AddCourseGroup> {
        val seen = LinkedHashMap<String, AddCourseGroup>()
        for (result in rows) {
            val partial = parseNode(result.node)
            val room = (result.classRoomNo ?: "").trim()
            val dedupedRoom = Course.dedupRooms(room)
            val partialClassroomMap = if (dedupedRoom.isEmpty()) emptyMap() else buildMap {
                for ((day, periods) in partial) {
                    for (period in periods) put("$day-$period", dedupedRoom)
                }
            }
            val existing = seen[result.courseNo]
            seen[result.courseNo] = if (existing != null) {
                existing.merged(result, partial, room, partialClassroomMap)
            } else {
                AddCourseGroup(
                    courseNo = result.courseNo,
                    courseName = result.courseName,
                    instructor = result.courseTeacher,
                    credits = result.creditPoint.toCreditsOrZero(),
                    classroom = result.classRoomNo ?: "",
                    enrolledCount = result.chooseStudent ?: 0,
                    maxCount = result.maxEnrollment,
                    schedule = partial,
                    classroomMap = partialClassroomMap,
                    nodeDisplay = result.node ?: "",
                    dimension = CourseService.firstNonEmpty(listOf(result.dimension)),
                    allYear = CourseService.firstNonEmpty(listOf(result.allYear)),
                    requireOption = CourseService.firstNonEmpty(listOf(result.requireOption)),
                )
            }
        }
        return seen.values.toList()
    }

    private fun AddCourseGroup.merged(
        result: CourseSearchResult,
        partial: Map<Int, List<String>>,
        room: String,
        partialClassroomMap: Map<String, String>,
    ): AddCourseGroup {
        val merged = schedule.toMutableMap()
        for ((day, periods) in partial) {
            merged[day] = (merged[day] ?: emptyList()) + periods
        }
        val newClassroom = when {
            classroom.isEmpty() -> Course.dedupRooms(room)
            room.isEmpty() -> classroom
            else -> Course.dedupRooms("$classroom, $room")
        }
        val nodeStr = when {
            nodeDisplay.isEmpty() -> result.node ?: ""
            result.node.isNullOrEmpty() -> nodeDisplay
            else -> "$nodeDisplay, ${result.node}"
        }
        return copy(
            classroom = newClassroom,
            schedule = merged,
            classroomMap = classroomMap + partialClassroomMap,
            nodeDisplay = nodeStr,
            // Same first-non-empty rule as CourseService.lookupOrFallback: the row naming the
            // dimension need not be the first seen.
            dimension = CourseService.firstNonEmpty(listOf(dimension, result.dimension)),
            allYear = CourseService.firstNonEmpty(listOf(allYear, result.allYear)),
            requireOption = CourseService.firstNonEmpty(listOf(requireOption, result.requireOption)),
        )
    }
}
