// A term's reset as far as files and the backend go: the backend first, then
// this phone, and this phone only once the backend has forgotten the term.

package org.ntust.app.tigerduck.ui.screen.classtable

import android.util.Log
import kotlinx.coroutines.CancellationException
import org.ntust.app.tigerduck.data.CourseTombstoneKeys
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.util.logSummary

internal class ClassTableTermReset(
    private val dataCache: DataCache,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Forgets [semester] through [deleteOnServer], then here: its hidden courses come back, its
     * cache goes, hand-typed courses included, and so do the custom names of the courses it
     * held. Returns those course numbers, or null when the backend could not be told (offline,
     * a session that expired) — nothing here has changed then, as on iOS
     * (`ClassTableViewModel.resetCourses`).
     *
     * Lifting the tombstones first, as this used to, brought the term's hidden courses back on
     * the refetch while everything else stayed: a reset half made. Wiping first and then failing
     * the DELETE would leave an empty grid with the server still full, to be merged back as
     * hand-added rows.
     */
    suspend fun run(semester: String, deleteOnServer: suspend () -> Unit): Set<String>? {
        try {
            deleteOnServer()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "deleteAllCourses failed, nothing reset: ${e.logSummary()}")
            return null
        }
        val stored = dataCache.loadDeletedCourseNos()
        val lifted = CourseTombstoneKeys.entriesResetting(semester, stored)
        if (lifted.isNotEmpty()) dataCache.saveDeletedCourseNos(stored - lifted)
        // The stamp is taken after the DELETE landed: a snapshot fetched
        // before this instant still carries the pre-reset roster — see
        // DataCache.saveSemesterResetAt.
        dataCache.saveSemesterResetAt(semester, clock())
        // The server has forgotten every number in this term, so a
        // hand-typed course re-added later is unknown to it again — see
        // CourseSyncReconciler.reconcileSemester.
        dataCache.saveServerKnownNos(dataCache.loadServerKnownNos() - semester)
        // Custom names are keyed by course number only, as on iOS, so a
        // course retaken in another term loses its name there as well.
        val held = dataCache.loadCourses(semester).mapTo(mutableSetOf()) { it.courseNo }
        val names = dataCache.loadCourseCustomNames()
        if (names.keys.any { it in held }) dataCache.saveCourseCustomNames(names - held)
        // The term's cache goes too: a reset means "start this term over",
        // and the roster the portal returns next is the whole of it.
        // Load-bearing for sync as well — with the old roster still on disk
        // next to an empty server term, a sync would push it back up, and
        // this device's upload releases its own reset tombstones, so the
        // reset would undo itself.
        dataCache.saveCourses(emptyList(), semester)
        return held
    }

    private companion object {
        const val TAG = "ClassTableVM"
    }
}
