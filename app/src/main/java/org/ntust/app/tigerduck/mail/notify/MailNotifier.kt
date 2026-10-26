package org.ntust.app.tigerduck.mail.notify

import org.ntust.app.tigerduck.mail.model.MailSummary

interface MailNotifier {
    fun postNewMail(folder: String, messages: List<MailSummary>)
    fun postAuthFailure()

    /** Takes down what [postAuthFailure] posted, for a sign-in that worked. */
    fun cancelAuthFailure()
    fun cancelMessage(uid: Long)

    /** Takes back what [postNewMail] just posted for [messages], by id: the system lists a new notification only some time later. */
    fun cancelPosted(messages: List<MailSummary>)
    fun cancelAll()
}
