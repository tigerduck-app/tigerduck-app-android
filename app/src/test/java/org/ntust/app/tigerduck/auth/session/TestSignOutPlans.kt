package org.ntust.app.tigerduck.auth.session

import org.ntust.app.tigerduck.mail.MailAccount

/** A school with no one to sign out, for plans a test runs another account's sign-out through. */
internal object NoSchool : SchoolPlan {
    override val held: Boolean get() = false

    override fun signOut(reason: SignOutReason) = Unit

    override fun finishOwedSignOut(): Boolean = false

    override fun finishOwedWipe() = Unit

    override fun holdClosed() = Unit

    override fun releaseClosed() = Unit
}

/** The plans of a test that signs out School Mail alone: [mail] is School Mail, and the rest hold nothing. */
internal fun mailSignOutPlans(mail: MailAccount): SignOutPlans = SignOutPlans(
    school = NoSchool,
    mail = AccountPlan({ mail.signedIn.value }, mail::signOut),
    library = AccountPlan({ false }) {},
    readLibraryAgain = {},
    leaveDemoLibrary = {},
)
