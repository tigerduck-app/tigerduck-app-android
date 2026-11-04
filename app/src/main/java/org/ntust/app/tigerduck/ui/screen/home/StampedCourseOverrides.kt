package org.ntust.app.tigerduck.ui.screen.home

import org.ntust.app.tigerduck.data.legacyPlacesOf
import org.ntust.app.tigerduck.push.CourseOverrideResult
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.sync.SyncField
import org.ntust.app.tigerduck.sync.reconcile.PullHolds
import org.ntust.app.tigerduck.ui.screen.classtable.CourseColorPick

/**
 * A pull's course colors and custom names, field by field (LWW D12): each takes the snapshot's
 * value, its absence included, but a field this phone keeps ([keeps], by its [SyncField] name),
 * whose own value stays. A color a hide or a reset took off the backend goes here too, and a name
 * cleared or absent goes.
 *
 * A change an older build queued with no term names its course by its Moodle id
 * ([org.ntust.app.tigerduck.data.fieldOf]); its field is asked by that id too, so it holds the
 * course whatever term the snapshot's row is in.
 *
 * A course with no Moodle id, added by hand here, has a color and names no device here can send:
 * the class table and a first sync send an override only by a Moodle id. No snapshot can hold one
 * this phone set, so their absence takes nothing away; a value the snapshot does hold, another
 * device's, is still taken.
 */
internal object StampedCourseOverrides {

    /**
     * [courses], [term]'s, with the colors of [overrides] as D12 has them, and the courses moved
     * off a color a later pick took ([CourseSyncReconciler.ColorOutcome]); null
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
                // One no device here can send stays, as a course no pick names.
                if (course.customColorHex != null && !course.moodleIdNumber.isNullOrEmpty()) cleared += course.courseNo
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
     * course's name in each locale the snapshot's, an empty one cleared, an absent one too but on a
     * course of [unsendable] (no course of its number here has a Moodle id to send it by), and those
     * of [held] (course number and locale) as they are. Null when nothing changes.
     */
    fun names(
        existing: Map<String, Map<String, String>>,
        overrides: List<CourseOverrideResult>,
        held: Set<Pair<String, String>>,
        unsendable: Set<String> = emptySet(),
    ): Map<String, Map<String, String>>? {
        // "" kept: a name some device cleared, which clears it here too.
        val server = mutableMapOf<String, MutableMap<String, String>>()
        for (o in overrides) server.getOrPut(o.courseNo ?: continue) { mutableMapOf() }.putAll(o.customNames)
        val merged = mutableMapOf<String, Map<String, String>>()
        for (no in existing.keys + server.keys) {
            val mine = existing[no].orEmpty()
            val theirs = server[no].orEmpty()
            val entry = (mine.keys + theirs.keys).mapNotNull { locale ->
                when {
                    no to locale in held -> mine[locale]
                    locale in theirs -> theirs.getValue(locale).ifEmpty { null }
                    no in unsendable -> mine[locale]
                    else -> null
                }?.let { locale to it }
            }.toMap()
            if (entry.isNotEmpty()) merged[no] = entry
        }
        return merged.takeIf { it != existing }
    }

    /**
     * The course numbers and locales whose name this phone keeps, from the [fields] it keeps: a
     * name's field by its course key names its number, and one by a Moodle id (an older build's
     * change with no term) every number [overrides] give that id, and every place the send reads
     * it as ([legacyPlacesOf] over [everyTerm], [current] and [catalogue]): each course of every term
     * here with the id, else the term and number the id names. Two terms' courses may share one,
     * and the change goes to one of them, so it holds both, as holding one too many is safe and
     * losing a waiting rename is not.
     */
    fun heldNames(
        fields: Set<String>,
        everyTerm: Map<String, List<Course>>,
        overrides: List<CourseOverrideResult>,
        current: String?,
        catalogue: List<String>,
    ): Set<Pair<String, String>> {
        val bySnapshot = overrides.mapNotNull { o -> o.courseNo?.let { o.moodleCourseId to it } }.groupBy({ it.first }, { it.second })
        return fields.flatMapTo(HashSet()) { field ->
            val (course, locale) = SyncField.nameParts(field) ?: return@flatMapTo emptyList()
            val numbers = SyncField.courseNoOf(course)?.let(::listOf)
                ?: (bySnapshot[course].orEmpty() + legacyPlacesOf(course, everyTerm, current, catalogue).map { it.courseNo })
            numbers.map { it to locale }
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
 * A pull's two merges over [term]'s courses (LWW D12), as Home's pass and the background's
 * run them: what this phone keeps is what [holds] names, and what the course overrides' channel
 * holds as each merge runs ([waiting]), read there, so a pick or a rename kept while the pull
 * runs holds too. [everyCourse] reads every term's courses on this phone, in the
 * step that writes the names, under the courses' lock and the names' (`BackgroundCourseOverrides.apply`),
 * so a course and its name saved before that step are both read by it: which course an older
 * build's change names by its Moodle id, in whatever term, and which course numbers no course here
 * has a Moodle id for, whose names nothing can send.
 */
internal class StampedMerges(
    private val overrides: List<CourseOverrideResult>,
    private val term: String,
    private val holds: PullHolds,
    private val waiting: suspend () -> Set<String>,
    private val everyCourse: suspend () -> Map<String, List<Course>>,
    // The current term and the catalogue's terms, which an older build's change by its Moodle id is read against.
    private val legacyTerms: suspend () -> Pair<String?, List<String>>,
) {
    /** The names as [StampedCourseOverrides.names] merges them over [current], the courses read here too. */
    suspend fun names(current: Map<String, Map<String, String>>): Map<String, Map<String, String>>? {
        val everyTerm = everyCourse()
        val (currentTerm, catalogue) = legacyTerms()
        // The waiting changes before the acknowledgments: a change that lands in between is
        // acknowledged before it leaves the lane (OutboxLane.settle), so one or the other holds it.
        val waitingNow = waiting()
        val held = StampedCourseOverrides.heldNames(waitingNow + holds.fields(), everyTerm, overrides, currentTerm, catalogue)
        val unsendable = everyTerm.values.flatten().groupBy { it.courseNo }
            .filterValues { all -> all.all { it.moodleIdNumber.isNullOrEmpty() } }.keys
        return StampedCourseOverrides.names(current, overrides, held, unsendable)
    }

    /** [courses] with the colors as [StampedCourseOverrides.colors] has them. */
    suspend fun colors(courses: List<Course>): CourseSyncReconciler.ColorOutcome? {
        // Read before the acknowledgments, which [PullHolds.keeps] reads as each color is asked; see [names].
        val held = waiting()
        return StampedCourseOverrides.colors(courses, term, overrides) { holds.keeps(it) || it in held }
    }
}
