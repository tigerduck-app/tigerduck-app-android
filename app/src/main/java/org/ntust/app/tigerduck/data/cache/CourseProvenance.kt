// What a write of a term's courses changed of each merged copy's provenance
// (Course.isUserAddedOnServer), for the full syncs under way (DataCache.CourseSync.retold). A
// pull's step that tells a copy from its snapshot applies only over the value the copy held when
// the sync began (compare-and-set): a school fetch that made the copy the school's since is newer
// than that snapshot (Greptile on PR #687).

package org.ntust.app.tigerduck.data.cache

import org.ntust.app.tigerduck.data.CourseTombstoneKeys
import org.ntust.app.tigerduck.shared.Course

internal object CourseProvenance {

    /**
     * [saved], a whole list of a term read before, each course [stored], the term as it stands,
     * holds saying of its row what the term says ([Course.isUserAddedOnServer]). Only a pull, a
     * school fetch and Use Local tell a copy what its row is, each over the term as it stands
     * (`DataCache.updateCourses`); a whole-list save from an older read (the colors' reset, Home's
     * relabel, Use Server's colors) that landed during a later sync put back a value told since,
     * which that sync's pull then took for newer than its snapshot and kept, and a school course
     * went up as an add at 0 (Codex on PR #687, round 7). A course the term does not hold keeps
     * its own. Returns the list to write and the term as [load] read it, null when not read.
     */
    suspend fun keptOver(saved: List<Course>, load: suspend () -> List<Course>): Pair<List<Course>, List<Course>?> {
        // Only a copy merged down says anything of its row; a list with none is written as given.
        if (saved.none { it.isManual && !it.moodleIdNumber.isNullOrEmpty() }) return saved to null
        val stored = load()
        return asStored(stored, saved) to stored
    }

    /** [saved] as [keptOver] keeps it over [stored], the term as it stands. */
    fun asStored(stored: List<Course>, saved: List<Course>): List<Course> {
        val told = stored.associateBy({ it.courseNo }, { it.isUserAddedOnServer })
        return saved.map { course ->
            if (course.courseNo !in told || told[course.courseNo] == course.isUserAddedOnServer) course
            else course.copy(isUserAddedOnServer = told[course.courseNo])
        }
    }

    /** The keys of [semester]'s courses held both [before] and [after] a write whose provenance it changed. */
    fun retold(semester: String, before: List<Course>, after: List<Course>): Set<String> {
        val was = before.associateBy({ it.courseNo }, { it.isUserAddedOnServer })
        return after.filter { it.courseNo in was && was[it.courseNo] != it.isUserAddedOnServer }
            .mapTo(mutableSetOf()) { CourseTombstoneKeys.key(semester, it.courseNo) }
    }
}
