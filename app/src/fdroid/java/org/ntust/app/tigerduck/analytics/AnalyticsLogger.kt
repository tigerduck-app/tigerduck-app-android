package org.ntust.app.tigerduck.analytics

import javax.inject.Inject
import javax.inject.Singleton

/**
 * F-Droid stub. The fdroid flavor ships without Google Play Services or any
 * Firebase SDK, so all analytics calls are no-ops — nothing leaves the device.
 *
 * Same FQN as the play-flavor implementation so callers in `main/` can inject
 * and call `log(...)` regardless of which flavor is being built.
 */
@Singleton
class AnalyticsLogger @Inject constructor() {
    fun log(event: String, params: Map<String, Any?> = emptyMap()) = Unit
    fun setUserProperty(name: String, value: String?) = Unit
    fun setEnabled(enabled: Boolean) = Unit
}
