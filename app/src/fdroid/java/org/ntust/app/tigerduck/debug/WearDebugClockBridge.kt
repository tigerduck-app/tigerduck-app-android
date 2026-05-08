package org.ntust.app.tigerduck.debug

import org.ntust.app.tigerduck.shared.clock.ClockOverride
import javax.inject.Inject
import javax.inject.Singleton

/**
 * F-Droid stub. The fdroid flavor ships without Google Play Services and so
 * cannot use the Wearable Data Layer to talk to a paired watch. Same FQN as
 * the play impl so callers don't care which flavor they're in.
 *
 * No constructor params — pulling Context here would force Hilt to wire it
 * for a method that does nothing.
 */
@Singleton
class WearDebugClockBridge @Inject constructor() {
    suspend fun push(override: ClockOverride?) = Unit
}
