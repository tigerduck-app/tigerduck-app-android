package org.ntust.app.tigerduck.update

import android.app.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ntust.app.tigerduck.data.model.ManualCheckResult
import org.ntust.app.tigerduck.data.model.PendingUpdate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * F-Droid stub. The fdroid flavor ships without Google Play Services, so
 * Play's update API is unavailable — and the F-Droid client notifies users
 * of updates out-of-band anyway, making an in-app prompt redundant
 * (issue #89).
 *
 * Same FQN as the play-flavor implementation so `MainActivity` in `main/`
 * can inject and call it regardless of which flavor is being built — same
 * pattern as `FcmBootstrap`. No GMS dependencies, so this compiles on
 * fdroid.
 */
@Singleton
class UpdateChecker @Inject constructor() {

    /** Permanently null on fdroid — the dialog never mounts. */
    val pendingUpdate: StateFlow<PendingUpdate?> = MutableStateFlow<PendingUpdate?>(null).asStateFlow()

    /** Permanently false on fdroid — the Settings spinner never spins. */
    val isCheckingForUpdate: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    /** Permanently null on fdroid — the result alert never mounts. */
    val lastManualCheckResult: StateFlow<ManualCheckResult?> = MutableStateFlow<ManualCheckResult?>(null).asStateFlow()

    fun maybePromptForUpdate() = Unit

    fun resume(activity: Activity) = Unit

    fun checkManually() = Unit

    fun acknowledgeManualCheckResult() = Unit

    fun onUpdateNow(activity: Activity) = Unit

    fun onLater() = Unit

    fun onSkipThisVersion() = Unit

    fun dismissPrompt() = Unit

    /** No-op: fdroid has no update prompt, so there is nothing to arm. */
    fun armForDebug() = Unit
}
