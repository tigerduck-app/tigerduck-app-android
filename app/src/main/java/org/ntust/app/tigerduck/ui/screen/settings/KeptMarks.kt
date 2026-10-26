package org.ntust.app.tigerduck.ui.screen.settings

import android.util.Log
import kotlinx.coroutines.CancellationException
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.ui.screen.home.AssignmentOverrideReconciler
import org.ntust.app.tigerduck.util.logSummary

/**
 * Sends what Use Local decided for each assignment ([marksNow]), reading this
 * phone's marks again before a request whenever [marksVersion] says they were
 * written since the last read. A tap made while the requests go out is this
 * phone's newer mark, kept to go up once the hold ends; the status read
 * before it would undo it. An id the tap left with nothing to send is
 * skipped. A mark that fails to go up is handed to [keep]
 * (`UnsentMarks.keep`): dropped, the next sync would take the server, which
 * never got it, as the truth.
 */
internal suspend fun sendKeptMarks(
    marksVersion: () -> Int,
    marksNow: suspend () -> Map<String, String>,
    send: suspend (assignmentId: Int, status: String) -> Unit,
    keep: suspend (assignmentId: String, status: String) -> Unit,
) {
    // Taken before each read: a write after it shows as a newer version.
    var readAt = marksVersion()
    var marks = marksNow()
    for (id in marks.keys.toList()) {
        if (marksVersion() != readAt) {
            readAt = marksVersion()
            marks = marksNow()
        }
        val status = marks[id] ?: continue
        val intId = id.toIntOrNull() ?: continue
        try {
            send(intId, status)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("SyncReenable", "[reenable] keep-local mark failed, kept to go up later: ${e.logSummary()}")
            keep(id, status)
        }
    }
}

/**
 * The server's view Use Local decides this phone's marks and course ignores
 * against: read again now, or, when that read fails, the one the re-enable
 * check compared when it asked the question ([checked]). That one can be
 * hours old, as the dialog may stay open that long, so a mark another device
 * set since gets no clear and stays; still better than none, with which an
 * assignment unmarked here but marked on the server would get no clear, and
 * once the hold ended the next sync would bring the server's mark back.
 */
internal suspend fun serverForUseLocal(
    fetch: suspend () -> BackendSyncResult,
    checked: BackendSyncResult?,
): BackendSyncResult? = try {
    fetch()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w("SyncReenable", "[reenable] keep-local read failed, using the check's: ${e.logSummary()}")
    checked
}

/**
 * Use Local's answer to [conflict] for assignment marks and course ignores:
 * this phone's marks go up as they stand at each request ([sendKeptMarks]),
 * with a clear for each mark only the server has, and the course ignores
 * after them ([pushCourseIgnores]). Both are decided against the server read
 * again now, or against the snapshot the check compared when that read fails
 * ([serverForUseLocal]).
 */
internal suspend fun keepLocalMarks(
    conflict: SettingsViewModel.ReenableConflict,
    fetch: suspend () -> BackendSyncResult,
    marksVersion: () -> Int,
    localMarks: suspend () -> Pair<Set<String>, Set<String>>,
    send: suspend (assignmentId: Int, status: String) -> Unit,
    keep: suspend (assignmentId: String, status: String) -> Unit,
    pushCourseIgnores: suspend (BackendSyncResult?) -> Unit,
) {
    val server = serverForUseLocal(fetch, conflict.checked)
    val serverIgnored = server?.ignoredIds.orEmpty()
    val serverCompleted = server?.completedIds.orEmpty()
    sendKeptMarks(
        marksVersion = marksVersion,
        marksNow = {
            val (ignored, completed) = localMarks()
            AssignmentOverrideReconciler.keepLocal(
                localIgnored = ignored,
                localCompleted = completed,
                serverIgnored = serverIgnored,
                serverCompleted = serverCompleted,
            )
        },
        send = send,
        keep = keep,
    )
    pushCourseIgnores(server)
}
