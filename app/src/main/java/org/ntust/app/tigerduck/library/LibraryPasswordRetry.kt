package org.ntust.app.tigerduck.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.auth.retryWhenDue
import org.ntust.app.tigerduck.shared.LibraryService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tries the stored library password again once a refusal's wait is over
 * ([LibraryService.storedPasswordRetryAt]), as the school's [org.ntust.app.tigerduck.auth.StoredPasswordRetry]
 * does, so a Library page left on the sign-in form signs in without a return to
 * the app, and a relaunch takes the wait up where it stood
 * (tigerduck-app/tigerduck-app#769). A refusal it gets is counted and starts the
 * next wait; a try that stops short of the library's answer, lost to the
 * network say, goes again a step further down the schedule. A sign-in the
 * library takes, by hand or by itself, and a sign-out end the count, and with
 * it the wait. The phone's only: the watch sends its password when its page
 * asks for a code, and not before the same wait is over.
 */
@Singleton
class LibraryPasswordRetry internal constructor(
    private val service: LibraryService,
    private val now: () -> Long,
    /** One try; true once it got the library's answer. */
    private val retry: suspend () -> Boolean = service::retryStoredPassword,
) {
    @Inject
    constructor(service: LibraryService) : this(service, System::currentTimeMillis)

    /** Keeps the tries going in [scope] while it runs. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        retryWhenDue(
            service.storedPasswordChanges,
            { service.storedPasswordRefusals },
            service::storedPasswordRetryAt,
            now,
            retry,
        )
    }
}
