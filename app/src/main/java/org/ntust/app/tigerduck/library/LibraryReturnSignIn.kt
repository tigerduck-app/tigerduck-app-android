package org.ntust.app.tigerduck.library

import org.ntust.app.tigerduck.auth.session.AccountSession
import org.ntust.app.tigerduck.network.NetworkChecker
import org.ntust.app.tigerduck.shared.LibraryService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The library's part of each launch and return to the app: read the sign-in
 * state again, which the flip and Settings follow, then sign in again with the
 * stored library credentials when the day-long token has run out, so the
 * Library page opens on a code. Not in demo mode, which reaches no server; the
 * other conditions are [LibraryService.signInIfCredentialed]'s.
 */
@Singleton
class LibraryReturnSignIn internal constructor(
    private val session: LibrarySession,
    private val libraryService: LibraryService,
    /** The school's: the demo account is signed in there, and reaches no server. */
    private val school: AccountSession,
    private val isOnline: () -> Boolean,
) {
    @Inject
    constructor(
        session: LibrarySession,
        libraryService: LibraryService,
        school: AccountSession,
        networkChecker: NetworkChecker,
    ) : this(session, libraryService, school, networkChecker::isAvailable)

    /** Returns whether it signed in. */
    suspend fun onAppResumed(): Boolean {
        session.refresh()
        if (school.isDemo) return false
        return libraryService.signInIfCredentialed(isOnline)
    }
}
