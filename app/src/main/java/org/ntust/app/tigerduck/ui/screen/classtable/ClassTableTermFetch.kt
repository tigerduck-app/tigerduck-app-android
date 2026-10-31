// One term's fetch as far as its list goes: the roster and the hand-added
// rows looked up, and the answers folded onto the term's list as it stands
// once they land. The lookups take seconds, and the user keeps editing
// meanwhile — a colour, a course added or deleted — so the list read before
// them would write those edits over; it is read again after, together with
// the tombstones, and folded in one go.

package org.ntust.app.tigerduck.ui.screen.classtable

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.ntust.app.tigerduck.data.CourseTombstoneKeys
import org.ntust.app.tigerduck.data.TranscriptTerms
import org.ntust.app.tigerduck.network.LookedUpCourse
import org.ntust.app.tigerduck.network.model.MoodleEnrolledCourse
import org.ntust.app.tigerduck.shared.Course

internal class ClassTableTermFetch(
    /**
     * One course as the school has it: QueryCourse's rows, answered, or else
     * `kept`, the row the term already has, or Moodle's stand-in, or null for
     * neither — `CourseService.lookupOrFallback`. A query that fails tells
     * `onFailed` too.
     */
    private val lookUpCourse: suspend (
        semester: String, courseNo: String, moodle: MoodleEnrolledCourse?, kept: Course?, onFailed: () -> Unit,
    ) -> LookedUpCourse,
) {
    /** What a term's lookups brought, for [fold]. */
    class Answers(
        /** The roster's courses, as the school has them. */
        val fetched: List<Course>,
        /** The hand-added courses the query answered, by number. */
        val lookedUp: Map<String, Course>,
        /** Whether every query went through; the warm of the other terms stops at one that did not. */
        val answered: Boolean,
        /**
         * Each roster course as its lookup came back, in the roster's order,
         * and whether QueryCourse answered it, for
         * [TranscriptTerms.rebuiltPastTerm].
         */
        val looked: List<LookedUpCourse> = emptyList(),
        /**
         * Set when [fetched] is a past term rebuilt from its transcript, see
         * [rebuilding]: what the rebuild drops, which [fold] takes off the
         * term's merged rows.
         */
        val rebuildDrops: Set<String>? = null,
        /**
         * Set when the term only gains from the transcript: [fold] removes
         * none of its courses, a course saved while the lookups ran, which
         * [fetched] does not list, included.
         */
        val onlyGains: Boolean = false,
    ) {
        /** Nothing came back: the term is left as it is. A rebuild always comes back, even empty. */
        val isEmpty: Boolean get() = rebuildDrops == null && fetched.isEmpty() && lookedUp.isEmpty()

        /**
         * These answers as [rebuilt], an ended term rebuilt from its
         * transcript: its courses are the term, and [fold] takes off the
         * merged rows it drops. The hand-added rows looked up stay refreshed.
         */
        fun rebuilding(rebuilt: TranscriptTerms.RebuiltTerm): Answers =
            Answers(rebuilt.courses, lookedUp, answered, looked, rebuildDrops = rebuilt.dropped)
    }

    /**
     * [rosterNos] looked up course by course, and with them the courses the
     * user added by hand, or that were merged from another device, that the
     * roster does not list, read off [before], the term's list as the lookups
     * begin (tigerduck-app-android#264): [ClassTableCourseMerge.mergeFetched]
     * keeps those as stored, so a room or time the school changed since they
     * were added never reached them. One the query does not answer keeps its
     * snapshot.
     *
     * [atMost] queries are in flight at once, or every one for null.
     */
    suspend fun lookUp(
        semester: String,
        rosterNos: List<String>,
        moodleByNo: Map<String, MoodleEnrolledCourse>,
        before: List<Course>,
        atMost: Int? = null,
    ): Answers = coroutineScope {
        val manualNos = ClassTableCourseMerge.manualNosToLookUp(before, rosterNos)
        if (rosterNos.isEmpty() && manualNos.isEmpty()) {
            return@coroutineScope Answers(emptyList(), emptyMap(), answered = true)
        }
        var failed = false
        val noteFailure = { failed = true }
        val permits = atMost?.let { Semaphore(it) }
        suspend fun ask(courseNo: String, moodle: MoodleEnrolledCourse?, kept: Course?): LookedUpCourse {
            val lookUp = suspend { lookUpCourse(semester, courseNo, moodle, kept, noteFailure) }
            return if (permits == null) lookUp() else permits.withPermit { lookUp() }
        }
        // A roster course whose query fails, or brings no rows, keeps its row
        // in the term, where Moodle's stand-in would take its times and room
        // (#417).
        val beforeByNo = before.associateBy { it.courseNo }
        val roster = rosterNos.map { courseNo ->
            async { ask(courseNo, moodleByNo[courseNo], beforeByNo[courseNo]) }
        }
        // Without Moodle to fall back on: a hand-added course the query does
        // not answer keeps its snapshot, where a stub would take its times.
        val manual = manualNos.map { courseNo ->
            async { ask(courseNo, null, null) }
        }
        val looked = roster.awaitAll()
        val lookedUp = manual.awaitAll().mapNotNull { lookup -> lookup.course?.let { lookup.courseNo to it } }.toMap()
        Answers(looked.mapNotNull { it.course }, lookedUp, answered = !failed, looked = looked)
    }

    /**
     * One term's commit of its [answers], read as [source] says, under the
     * caller's lock, before anything of the term is saved: course selection's
     * answer as fetched; a class the transcript adds only with class times,
     * and nothing the term holds by the fold removed
     * ([TranscriptTerms.drawnCourses], [Answers.onlyGains]); an ended term as
     * its transcript rebuilds it ([TranscriptTerms.rebuiltPastTerm]), whose
     * drops [recordFinalRoster] writes first, so a sync landing before the
     * save cannot merge the junk back. Then the term [fold]s, through the
     * caller's [foldLatest], for the caller to save and upload.
     *
     * [latest] is the term's list as it stands; [waitingAdds] the courses
     * whose add has not gone up yet, which are never dropped. Null leaves the
     * term as it is: nothing came back, or [fold] gave nothing.
     *
     * As iOS `AppServiceBridge.fetchCourses`' save (tigerduck-app#890).
     */
    suspend fun committed(
        answers: Answers,
        source: ClassTableTermSource,
        semester: String,
        moodleNos: Collection<String>,
        latest: suspend () -> List<Course>,
        waitingAdds: suspend () -> Set<String>,
        recordFinalRoster: suspend (TranscriptTerms.RebuiltTerm) -> Unit,
        fold: suspend (Answers) -> List<Course>?,
    ): List<Course>? {
        val drawn = when (source) {
            is ClassTableTermSource.Rebuild -> {
                val rebuilt = TranscriptTerms.rebuiltPastTerm(
                    answers.looked, source.report, semester, latest(), moodleNos, waitingAdds(),
                )
                recordFinalRoster(rebuilt)
                answers.rebuilding(rebuilt)
            }
            is ClassTableTermSource.Gaining -> {
                val cached = latest().mapTo(mutableSetOf()) { it.courseNo }
                val fetched = TranscriptTerms.drawnCourses(answers.fetched, fromSelection = false, cached = cached)
                Answers(fetched, answers.lookedUp, answers.answered, answers.looked, onlyGains = true)
            }
            is ClassTableTermSource.Selection, ClassTableTermSource.Unanswered -> answers
        }
        if (drawn.isEmpty) return null
        return fold(drawn)
    }

    /**
     * The term's list after its lookups: [answers] folded onto [latest], the
     * list as it stands once they land, with [deletedNos] applied whichever
     * way the answers came. The caller reads the two together and folds with
     * nothing suspending in between: an edit made while the lookups ran — a
     * colour, a course added or deleted — is in them, where the list read
     * before the lookups would write it over, and a course deleted then is
     * not brought back by its own answer.
     */
    /**
     * [fold] over a snapshot of the term taken in the one order that cannot
     * miss an edit made meanwhile. Every read that suspends comes first:
     * [cachedCourses], and [storedTombstones] after it, so a tombstone whose
     * write landed during the cache read — retiring its pending edit, which
     * leaves the file as the only record of it — is in what is read. Then,
     * with nothing suspending in between: the list is chosen, [shownCourses]
     * when the list on screen is this term's by now, else the cache just
     * read; the edits still on their way are laid over the file
     * ([withPendingEdits]); and the fold is made. The term can come on screen
     * during the reads, which is why the list is chosen after them.
     *
     * [editGeneration] counts the term's in-place edits, bumped at the tap.
     * Read before the reads and again after them: an edit in between may have
     * reached the cache only after it was read, and the list on screen may
     * have left for another term since, leaving that stale read as the only
     * source — a course added then would be written back out, and its own
     * upload, queued behind this commit, would find it gone. The snapshot is
     * taken again then, [SNAPSHOT_ATTEMPTS] times; null when the term was
     * edited throughout, to be left as it is for the next fetch.
     */
    suspend fun foldLatest(
        answers: Answers,
        semester: String,
        editGeneration: () -> Int,
        cachedCourses: suspend () -> List<Course>,
        storedTombstones: suspend () -> Set<String>,
        shownCourses: () -> List<Course>?,
        withPendingEdits: (Set<String>) -> Set<String>,
        names: () -> CustomNameMap,
        locale: String,
    ): List<Course>? {
        repeat(SNAPSHOT_ATTEMPTS) {
            val generation = editGeneration()
            val cached = cachedCourses()
            val stored = storedTombstones()
            if (editGeneration() != generation) return@repeat
            val latest = shownCourses() ?: cached
            return fold(answers, latest, names(), locale, CourseTombstoneKeys.hiddenIn(semester, withPendingEdits(stored)))
        }
        return null
    }

    private companion object {
        /** Snapshots a commit tries before leaving a term edited throughout them to the next fetch. */
        const val SNAPSHOT_ATTEMPTS = 3
    }

    fun fold(
        answers: Answers,
        latest: List<Course>,
        names: CustomNameMap,
        locale: String,
        deletedNos: Set<String>,
    ): List<Course> {
        val refreshed = ClassTableCourseMerge.withLookedUp(latest, answers.lookedUp)
        val rebuildDrops = answers.rebuildDrops
        return when {
            // A rebuilt past term is its transcript's, even when that is
            // nothing. The merge keeps manual rows, so the merged ones it
            // drops go first: kept in the list, a sync would hide them for
            // good, or send them back up to a server that has none. A course
            // added here has no Moodle ID and stays.
            rebuildDrops != null -> ClassTableCourseMerge.mergeFetched(
                answers.fetched,
                refreshed.filterNot { it.isManual && !it.moodleIdNumber.isNullOrEmpty() && it.courseNo in rebuildDrops },
                names, locale, deletedNos,
            )
            answers.fetched.isNotEmpty() -> ClassTableCourseMerge.mergeFetched(
                answers.fetched, refreshed, names, locale, deletedNos, alsoKeeps = { answers.onlyGains },
            )
            // The roster brought nothing, so the term stays as it is, its
            // hand-added rows refreshed; a merge over nothing would drop the
            // rest.
            else -> CourseNameOverrides.resolve(refreshed, names, locale).filter { it.courseNo !in deletedNos }
        }
    }
}
