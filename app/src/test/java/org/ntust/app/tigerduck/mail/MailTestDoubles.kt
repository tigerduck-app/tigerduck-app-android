package org.ntust.app.tigerduck.mail

import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.model.MailBody
import org.ntust.app.tigerduck.mail.model.MailFlags
import org.ntust.app.tigerduck.mail.model.MailSummary
import org.ntust.app.tigerduck.mail.notify.MailNotifier
import org.ntust.app.tigerduck.mail.store.MailCredentialStore
import org.ntust.app.tigerduck.mail.sync.MailBackgroundScheduler

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
    override fun postNewMail(folder: String, messages: List<MailSummary>) { posted += messages }
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
        ),
    )
    override fun matches(studentId: String, password: String) = box.matches(studentId, password)
    override fun mailbox() = box
}
