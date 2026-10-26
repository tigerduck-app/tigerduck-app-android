package org.ntust.app.tigerduck.ui.screen.home

import android.util.Log
import kotlinx.coroutines.CancellationException
import org.ntust.app.tigerduck.util.logSummary

/**
 * Sends this device's marks up as the account's first sync of them here — see
 * [AssignmentOverrideReconciler.isFirstUpload]. True only once every mark has
 * landed: a server still empty because a PATCH failed would otherwise read as
 * "cleared elsewhere" on the next sync, and wipe the mark it never got. The
 * other marks still go up after a failure, and the one that failed goes to
 * [keep] (`UnsentMarks.keep`): once the others reach the server it is no
 * longer empty, so the next sync is no first sync, and only that protects
 * the mark and sends it again. An id that is not a Moodle assignment id is
 * skipped.
 */
internal suspend fun sendFirstSyncMarks(
    localIgnored: Set<String>,
    localCompleted: Set<String>,
    keep: suspend (assignmentId: String, status: String) -> Unit,
    send: suspend (assignmentId: Int, status: String) -> Unit,
): Boolean {
    var allSent = true
    val marks = localIgnored.associateWith { AssignmentOverrideReconciler.STATUS_IGNORED } +
        localCompleted.associateWith { AssignmentOverrideReconciler.STATUS_COMPLETED }
    for ((id, status) in marks) {
        val intId = id.toIntOrNull() ?: continue
        try {
            send(intId, status)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            allSent = false
            Log.w("FirstSyncMarks", "[Sync] first-sync mark upload failed, kept: ${e.logSummary()}")
            keep(id, status)
        }
    }
    return allSent
}
