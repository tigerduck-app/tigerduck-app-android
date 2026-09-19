package org.ntust.app.tigerduck.mail.store

/** Where the mail student ID and password live. Implemented by CredentialManager. */
interface MailCredentialStore {
    var mailStudentId: String?
    var mailPassword: String?
    fun clearMailCredentials()
}
