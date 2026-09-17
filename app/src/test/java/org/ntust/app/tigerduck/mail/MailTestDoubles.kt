package org.ntust.app.tigerduck.mail

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.ntust.app.tigerduck.mail.imap.SpecialFolder
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.model.MailBody
import org.ntust.app.tigerduck.mail.model.MailFlags
import org.ntust.app.tigerduck.mail.model.MailSummary
import org.ntust.app.tigerduck.mail.notify.MailNotifier
import org.ntust.app.tigerduck.mail.store.MailCredentialStore
import org.ntust.app.tigerduck.mail.sync.MailBackgroundScheduler

/**
 * Stands in for the app-wide `@ApplicationScope`: the only thing [MailAccount]
 * runs there is sign-out's cache wipe, so a plain supervised scope is enough.
 * A test that asserts on that work waits for it (it lands on [Dispatchers.IO]).
 */
fun testApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

class InMemoryCredentialStore : MailCredentialStore {
    override var mailStudentId: String? = null
    override var mailPassword: String? = null
    override fun clearMailCredentials() {
        mailStudentId = null
        mailPassword = null
    }
}

class RecordingScheduler : MailBackgroundScheduler {
    var scheduled = 0
    var cancelled = 0
    override fun schedule() { scheduled++ }
    override fun cancel() { cancelled++ }
}

class RecordingNotifier : MailNotifier {
    val posted = mutableListOf<List<MailSummary>>()
    var authFailures = 0
    val cancelledUids = mutableListOf<Long>()
    var cancelledAll = 0

    /** Fires inside [postNewMail] -- lets a test act at the exact point the check notifies. */
    var onPostNewMail: (() -> Unit)? = null
    override fun postNewMail(folder: String, messages: List<MailSummary>) {
        posted += messages
        onPostNewMail?.invoke()
    }
    override fun postAuthFailure() { authFailures++ }
    override fun cancelMessage(uid: Long) { cancelledUids += uid }
    override fun cancelAll() { cancelledAll++ }
}

class FakeDemoGate(override var appDemoActive: Boolean = false) : MailDemoGate {
    val box = DemoMailbox(
        studentId = "B10000099", password = "demo", displayName = "示範同學",
        messages = listOf(
            DemoMail(
                MailSummary(1001, MailAddress("教務處", "office@mail.ntust.edu.tw"), emptyList(), emptyList(), emptyList(),
                    "期中考時間公告", null, null, MailFlags.NONE, 100, false, "<d1@x>", null, null),
                MailBody("<p>期中考</p>", "期中考", emptyList(), emptyMap()),
            ),
            DemoMail(
                MailSummary(1002, MailAddress("示範同學", "b10000099@mail.ntust.edu.tw"), emptyList(),
                    listOf(MailAddress("系辦公室", "office2@mail.ntust.edu.tw")), emptyList(),
                    "詢問事項", null, null, MailFlags.NONE.copy(seen = true, draft = true), 50, false, "<d2@x>", null, null),
                MailBody(null, "詢問內容", emptyList(), emptyMap()),
                folder = SpecialFolder.DRAFTS,
            ),
            DemoMail(
                MailSummary(1003, MailAddress("示範同學", "b10000099@mail.ntust.edu.tw"), emptyList(),
                    listOf(MailAddress("教務處", "office@mail.ntust.edu.tw")), emptyList(),
                    "Re: 期中考時間公告", null, null, MailFlags.NONE.copy(seen = true), 50, false, "<d3@x>", null, null),
                MailBody(null, "收到，謝謝", emptyList(), emptyMap()),
                folder = SpecialFolder.SENT,
            ),
        ),
    )
    override fun matches(studentId: String, password: String) = box.matches(studentId, password)
    override fun mailbox() = box
}
