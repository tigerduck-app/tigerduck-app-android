// What a write of a term's courses changed of each merged copy's provenance
// (Course.isUserAddedOnServer), for the full syncs under way (DataCache.CourseSync.retold). A
// pull's step that tells a copy from its snapshot applies only over the value the copy held when
// the sync began (compare-and-set): a school fetch that made the copy the school's since is newer
// than that snapshot (Greptile on PR #687).

package org.ntust.app.tigerduck.data.cache

import org.ntust.app.tigerduck.data.CourseTombstoneKeys
import org.ntust.app.tigerduck.shared.Course

internal object CourseProvenance {

    /** The keys of [semester]'s courses held both [before] and [after] a write whose provenance it changed. */
    fun retold(semester: String, before: List<Course>, after: List<Course>): Set<String> {
        val was = before.associateBy({ it.courseNo }, { it.isUserAddedOnServer })
        return after.filter { it.courseNo in was && was[it.courseNo] != it.isUserAddedOnServer }
            .mapTo(mutableSetOf()) { CourseTombstoneKeys.key(semester, it.courseNo) }
    }
}
