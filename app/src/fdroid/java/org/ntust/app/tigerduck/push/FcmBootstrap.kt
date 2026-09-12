package org.ntust.app.tigerduck.push

import javax.inject.Inject
import javax.inject.Singleton

/**
 * F-Droid stub. The fdroid flavor ships without Google Play Services, so
 * there is no FCM token to fetch. Bulletins still work — the list view
 * polls on open / pull-to-refresh — there's just no real-time push.
 *
 * Same FQN as the play-flavor implementation so `main/` can inject it and
 * call `start()` and `retryRegistrationIfDue()` regardless of which flavor
 * is being built.
 *
 * Dependencies are intentionally NOT mirrored from the play impl: pulling in
 * `PushRegistrationService` and the `@ApplicationScope` `CoroutineScope`
 * here would force Hilt to build the entire push graph on fdroid where
 * both calls are no-ops. Per-flavor `@Inject` constructors are fine —
 * callers inject by type and Hilt resolves the binding per flavor.
 */
@Singleton
class FcmBootstrap @Inject constructor() {
    fun start() = Unit

    /** No FCM token here, so there is never a registration to retry. */
    fun retryRegistrationIfDue() = Unit
}
