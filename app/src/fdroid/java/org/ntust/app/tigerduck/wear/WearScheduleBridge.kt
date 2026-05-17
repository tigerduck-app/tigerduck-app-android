package org.ntust.app.tigerduck.wear

import javax.inject.Inject
import javax.inject.Singleton

/**
 * F-Droid stub. The fdroid flavor ships without Google Play Services and so
 * cannot use the Wearable Data Layer to talk to a paired watch. Wear OS pairing
 * itself depends on Play Services on the phone, so there is nothing to sync to
 * even if we wanted to.
 *
 * Same FQN as the play-flavor implementation so TigerDuckApp.kt and other
 * src/main/ callers can inject and invoke `publish()` regardless of which
 * flavor is being built — Hilt resolves the binding per flavor.
 *
 * Dependencies are intentionally NOT mirrored from the play impl. Pulling
 * DataCache / AuthService / AppPreferences in here would force Hilt to wire
 * them on fdroid for a method that does nothing. The play impl can keep its
 * @Inject constructor; this stub takes no constructor params.
 */
@Singleton
class WearScheduleBridge @Inject constructor() {
    suspend fun publish() = Unit
    suspend fun publishLibraryCredentials() = Unit
}
