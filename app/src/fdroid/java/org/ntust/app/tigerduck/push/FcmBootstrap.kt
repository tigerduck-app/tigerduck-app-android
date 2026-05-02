package org.ntust.app.tigerduck.push

import javax.inject.Inject
import javax.inject.Singleton

/**
 * F-Droid stub. The fdroid flavor ships without Google Play Services, so
 * there is no FCM token to fetch. Bulletins still work — the list view
 * polls on open / pull-to-refresh — there's just no real-time push.
 *
 * Same FQN as the play-flavor implementation so TigerDuckApp.kt in main/
 * can inject and call `start()` regardless of which flavor is being built.
 *
 * Dependencies are intentionally NOT mirrored from the play impl: pulling in
 * `PushRegistrationService` and the `@ApplicationScope` `CoroutineScope`
 * here would force Hilt to build the entire push graph on fdroid where
 * `start()` is a no-op. Per-flavor `@Inject` constructors are fine —
 * `TigerDuckApp` injects by type and Hilt resolves the binding per flavor.
 */
@Singleton
class FcmBootstrap @Inject constructor() {
    fun start() = Unit
}
