package org.ntust.app.tigerduck.ui.screen.home

import org.ntust.app.tigerduck.push.CourseOverrideResult
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.sync.SyncField
import org.ntust.app.tigerduck.sync.reconcile.PullHolds
import org.ntust.app.tigerduck.ui.screen.classtable.CourseColorPick

/**
 * A `/v4` pull's course colors and custom names, field by field (LWW D12): each takes the
 * snapshot's value, its absence included, but a field this phone keeps ([keeps], by its
 * [SyncField] name), whose own value stays. A color a hide or a reset took off the backend goes
 * here too, and a name cleared or absent goes. The `/v3` merges ([CourseSyncReconciler.applyColorOverrides],
 * [CourseSyncReconciler.mergeCustomNames]) stay until the `/v3` rules go (Z2).
 *
 * A change an older build queued with no term names its course by its Moodle id
 * ([org.ntust.app.tigerduck.data.fieldOf]); its field is asked by that id too, so it holds the
 * course whatever term the snapshot's row is in.
 */
internal object StampedCourseOverrides {

    /**
     * [courses], [term]'s, with the colors of [overrides] as D12 has them, and the courses moved
     * off a color a later pick took, as [CourseSyncReconciler.applyColorOverrides] reports them; null
     * when nothing changes. The snapshot's picks go oldest first by their stamp, a color this phone
     * keeps last, as the latest pick, so a course on its color moves off it here as it will
     * everywhere once the color lands.
     */
    fun colors(
        courses: List<Course>,
        term: String,
        overrides: List<CourseOverrideResult>,
        keeps: (String) -> Boolean,
    ): CourseSyncReconciler.ColorOutcome? {
        if (courses.isEmpty()) return null
        val serverHex = mutableMapOf<String, String>()
        val cleared = mutableSetOf<String>()
        val picks = courses.mapNotNull { course ->
            val key = SyncField.courseKey(term, course.courseNo)
            val held = keeps(SyncField.color(key)) || course.moodleIdNumber?.let { keeps(SyncField.color(it)) } == true
            val row = rowOf(course, key, term, overrides)
            val hex = if (held) course.customColorHex else row?.colorHex
            if (hex == null) {
                if (course.customColorHex != null) cleared += course.courseNo
                return@mapNotNull null
            }
            serverHex[course.courseNo] = hex.uppercase()
            Triple(course.courseNo, hex, if (held) Long.MAX_VALUE else row?.colorHexHlc ?: Long.MIN_VALUE)
        }.sortedWith(compareBy({ it.third }, { it.first }))
        val start = courses.map { if (it.courseNo in cleared) it.copy(customColorHex = null) else it }
        return colorPicksApplied(courses, start, picks.map { it.first to it.second }, serverHex)
    }

    /**
     * [existing] names (course number to locale to name) with [overrides]' as D12 has them: each
     * course's name in each locale the snapshot's, an empty or absent one cleared, but those of
     * [held] (course number and locale), which stay as they are. Null when nothing changes.
     */
    fun names(
        existing: Map<String, Map<String, String>>,
        overrides: List<CourseOverrideResult>,
        held: Set<Pair<String, String>>,
    ): Map<String, Map<String, String>>? {
        val server = mutableMapOf<String, MutableMap<String, String>>()
        for (o in overrides) {
            val no = o.courseNo ?: continue
            val entry = server.getOrPut(no) { mutableMapOf() }
            for ((locale, name) in o.customNames) if (name.isEmpty()) entry.remove(locale) else entry[locale] = name
        }
        val merged = mutableMapOf<String, Map<String, String>>()
        for (no in existing.keys + server.keys) {
            val mine = existing[no].orEmpty()
            val theirs = server[no].orEmpty()
            val entry = (mine.keys + theirs.keys).mapNotNull { locale ->
                (if (no to locale in held) mine[locale] else theirs[locale])?.let { locale to it }
            }.toMap()
            if (entry.isNotEmpty()) merged[no] = entry
        }
        return merged.takeIf { it != existing }
    }

    /**
     * The course numbers and locales whose name this phone keeps, from the [fields] it keeps: a
     * name's field by its course key names its number, and one by a Moodle id (an older build's
     * change with no term) the number [courses] or [overrides] give that id.
     */
    fun heldNames(fields: Set<String>, courses: List<Course>, overrides: List<CourseOverrideResult>): Set<Pair<String, String>> {
        val byMoodleId = overrides.mapNotNull { o -> o.courseNo?.let { o.moodleCourseId to it } }.toMap() +
            courses.mapNotNull { c -> c.moodleIdNumber?.let { it to c.courseNo } }
        return fields.mapNotNullTo(HashSet()) { field ->
            val (course, locale) = SyncField.nameParts(field) ?: return@mapNotNullTo null
            (SyncField.courseNoOf(course) ?: byMoodleId[course])?.let { it to locale }
        }
    }

    // The snapshot's row of [course], [key] in [term]: by its key, else by its Moodle id, else by its number in the term.
    private fun rowOf(course: Course, key: String, term: String, overrides: List<CourseOverrideResult>): CourseOverrideResult? =
        overrides.find { it.courseKey == key }
            ?: course.moodleIdNumber?.let { id -> overrides.find { it.moodleCourseId == id } }
            ?: overrides.find { it.courseNo == course.courseNo && it.semester == term }
}

/**
 * [start], [original] with the colors that went cleared, with each of [picks] (course number and
 * color, in the order they were made) made as a pick on the class table is ([CourseColorPick]), and
 * the courses that moved off a color a later pick took, those whose color is not [serverHex]'s. Null
 * when nothing changed. Both apps apply the server's colors this way
 * (tigerduck-app-android#295, tigerduck-app/tigerduck-app#252).
 */
internal fun colorPicksApplied(
    original: List<Course>,
    start: List<Course>,
    picks: List<Pair<String, String>>,
    serverHex: Map<String, String>,
): CourseSyncReconciler.ColorOutcome? {
    var updated = start
    val displaced = mutableSetOf<String>()
    for ((courseNo, hex) in picks) {
        val pick = CourseColorPick.assign(updated, courseNo, hex, CourseColorPick.shownColors(updated))
        updated = pick.courses
        displaced += pick.changed.keys - courseNo
    }
    val moved = updated
        .filter { it.courseNo in displaced && it.customColorHex?.uppercase() != serverHex[it.courseNo] }
        .mapNotNull { course -> course.customColorHex?.let { course.courseNo to it } }
        .toMap()
    return if (updated == original && moved.isEmpty()) null else CourseSyncReconciler.ColorOutcome(updated, moved)
}

/**
 * A `/v4` pull's two merges over [term]'s courses (LWW D12), as Home's pass and the background's
 * run them: what this phone keeps is what [holds] names, and what the course overrides' channel
 * holds as each merge runs ([waiting]), read again there, so a pick or a rename kept since the
 * pull read the outbox holds too.
 */
internal class StampedMerges(
    private val overrides: List<CourseOverrideResult>,
    private val term: String,
    private val holds: PullHolds,
    private val waiting: suspend () -> Set<String>,
) {
    /** The names as [StampedCourseOverrides.names] merges them over [current], [courses] naming the Moodle ids. */
    suspend fun names(current: Map<String, Map<String, String>>, courses: List<Course>): Map<String, Map<String, String>>? {
        val held = StampedCourseOverrides.heldNames(holds.fields() + waiting(), courses, overrides)
        return StampedCourseOverrides.names(current, overrides, held)
    }

    /** [courses] with the colors as [StampedCourseOverrides.colors] has them. */
    suspend fun colors(courses: List<Course>): CourseSyncReconciler.ColorOutcome? {
        val held = waiting()
        return StampedCourseOverrides.colors(courses, term, overrides) { holds.keeps(it) || it in held }
    }
}
