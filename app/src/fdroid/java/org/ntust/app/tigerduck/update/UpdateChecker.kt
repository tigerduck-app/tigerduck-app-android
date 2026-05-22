package org.ntust.app.tigerduck.update

import android.app.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * F-Droid stub. The fdroid flavor ships without Google Play Services, so
 * Play's in-app update API is unavailable — and the F-Droid client notifies
 * users of updates out-of-band anyway, making an in-app prompt redundant
 * (issue #89).
 *
 * Same FQN as the play-flavor implementation so `MainActivity` in `main/` can
 * inject and call it regardless of which flavor is being built — same pattern
 * as `FcmBootstrap`. No GMS dependencies, so this compiles on fdroid.
 */
@Singleton
class UpdateChecker @Inject constructor() {

    /** Never flips — fdroid has no in-app update flow. */
    val installReady: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    fun maybePromptForUpdate(activity: Activity) = Unit

    fun resume(activity: Activity) = Unit

    fun completeUpdate() = Unit
}
