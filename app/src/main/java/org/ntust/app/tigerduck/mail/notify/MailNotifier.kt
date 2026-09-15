package org.ntust.app.tigerduck.mail.notify

import org.ntust.app.tigerduck.mail.model.MailSummary

interface MailNotifier {
    fun postNewMail(folder: String, messages: List<MailSummary>)
    fun postAuthFailure()
    fun cancelMessage(uid: Long)
    fun cancelAll()
}
