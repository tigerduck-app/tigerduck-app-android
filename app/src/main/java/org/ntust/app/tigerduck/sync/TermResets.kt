package org.ntust.app.tigerduck.sync

import org.ntust.app.tigerduck.data.PendingCourseAdds
import javax.inject.Inject

/**
 * The one way this phone resets a term on the backend. The class table's reset and Settings' Use
 * Local both go through [run], and so must any new one: `TermResetsWiringTest` fails a term
 * DELETE sent from anywhere else.
 *
 * [reset] sends the term's DELETE, with whatever goes with it, under the adds' send lock
 * ([PendingCourseAdds.removing]), so no course add and no school fetch's upload goes beside it.
 * Once it lands (a non-null result), the term's school uploads waiting for this process's first
 * pull, or let go by it and waiting for that lock, are let go in the same hold
 * ([SchoolUploads.forgetTerm]). Sent after the reset, a fetch's term from before it would undo
 * it. A commit made after the reset may have stored its roster again, so the upload would go
 * over the student's choice as the whole roster (Codex on PR R, fix round 6). A reset that did
 * not land (null, or a throw) changed nothing, and the waiting uploads stay.
 *
 * The send lock comes first and the term's upload ledger (`UploadLedger.holdingUploads`, inside
 * the DELETE) after it, as for every upload sent under that lock. Nothing holding the ledger
 * takes the send lock, so no other order exists to deadlock with.
 */
class TermResets @Inject constructor(
    private val adds: PendingCourseAdds,
    private val uploads: SchoolUploads,
) {
    suspend fun <T : Any> run(semester: String, reset: suspend () -> T?): T? =
        adds.removing { reset()?.also { uploads.forgetTerm(semester) } }
}
