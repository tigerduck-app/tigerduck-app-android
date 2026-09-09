// Semester scoping for the deleted-course store.
//
// `deleted_courses.json` began life as a flat list of bare course numbers,
// written when only the current term ever synced. Now that every semester
// reconciles against the backend (see CourseSyncReconciler), a bare entry is
// a wildcard: hiding 1142's CS101 also hid the CS101 the student retook in
// 1151, and the reconcile un-hid it again the moment any term listed it.
//
// Entries are therefore keyed "<semester>:<courseNo>". Old bare entries stay
// valid and keep hiding everywhere — [isHidden] and [hiddenIn] accept both —
// so the store's JSON shape never changes and no DataMigration step is
// needed. [migrateLegacyEntries] upgrades them opportunistically once a
// roster can say which terms they belonged to.

package org.ntust.app.tigerduck.data

object CourseTombstoneKeys {

    private const val SEPARATOR = ':'

    fun key(semester: String, courseNo: String): String = "$semester$SEPARATOR$courseNo"

    /**
     * Whether [courseNo] is hidden in [semester] — by this term's scoped key,
     * or by a legacy bare entry that hides it in every term.
     */
    fun isHidden(courseNo: String, semester: String, tombstones: Set<String>): Boolean =
        courseNo in tombstones || key(semester, courseNo) in tombstones

    /**
     * The bare course numbers hidden in [semester].
     *
     * Call sites that already filter with `courseNo !in deletedNos` swap the
     * raw store for this and keep their shape. Resolving once per term is
     * also why the scoped keys cost nothing at the read sites: they never see
     * a key, only the numbers that term hides.
     */
    fun hiddenIn(semester: String, tombstones: Set<String>): Set<String> {
        val prefix = key(semester, "")
        val result = mutableSetOf<String>()
        for (entry in tombstones) {
            when {
                SEPARATOR !in entry -> result.add(entry)
                entry.startsWith(prefix) -> result.add(entry.removePrefix(prefix))
            }
        }
        return result
    }

    /** Hides [courseNo] in [semester] only. */
    fun hide(courseNo: String, semester: String, tombstones: Set<String>): Set<String> =
        tombstones + key(semester, courseNo)

    /**
     * Un-hides [courseNo] in [semester], dropping the scoped key *and* any
     * legacy bare entry — a bare entry hides in every term, so leaving it
     * would silently ignore the un-hide.
     */
    fun unhide(courseNo: String, semester: String, tombstones: Set<String>): Set<String> =
        tombstones - key(semester, courseNo) - courseNo

    /**
     * What resetting [semester] drops: that term's scoped keys plus every
     * legacy bare entry, since those hide in all terms and a reset of this
     * one has to lift them.
     */
    fun entriesResetting(semester: String, tombstones: Set<String>): Set<String> {
        val prefix = key(semester, "")
        return tombstones.filterTo(mutableSetOf()) {
            it.startsWith(prefix) || SEPARATOR !in it
        }
    }

    /**
     * Pins legacy bare entries to the terms whose roster carries the course.
     *
     * [rosters] maps semester to the course numbers cached for it. An entry
     * no roster knows stays bare — still hidden everywhere — until a roster
     * turns up, so a student who has not opened an old term yet does not lose
     * that term's deletions. A no-op once nothing bare is left.
     */
    fun migrateLegacyEntries(
        entries: Set<String>,
        rosters: Map<String, Set<String>>,
    ): Set<String> {
        val result = entries.toMutableSet()
        for (courseNo in entries) {
            if (SEPARATOR in courseNo) continue
            val terms = rosters.filterValues { courseNo in it }.keys
            if (terms.isEmpty()) continue
            result.remove(courseNo)
            terms.forEach { result.add(key(it, courseNo)) }
        }
        return result
    }
}
