package org.ntust.app.tigerduck.update

import java.util.concurrent.TimeUnit

/**
 * Pure gating logic for the in-app update prompt. Kept Android-free so it is
 * unit-testable and so the flavor-specific [UpdateChecker] (play) only has to
 * supply raw values from `AppUpdateInfo`.
 *
 * "Don't nag" policy (issue #89): only prompt for updates that are genuinely
 * stale, never re-prompt for the same available version inside a cooldown
 * window, and honour an explicit "Skip this version" tap indefinitely. A
 * newer versionCode bypasses both the cooldown and the skip — those only
 * suppress the exact version the user already saw.
 */
object UpdatePromptGate {

    /** Minimum days the installed version must be behind before prompting. */
    const val STALENESS_THRESHOLD_DAYS = 3

    /** Re-prompt cooldown for the same available versionCode after "Later". */
    val COOLDOWN_MS: Long = TimeUnit.DAYS.toMillis(7)

    /**
     * @param stalenessDays Play's `clientVersionStalenessDays()` — null when
     *   Play has not yet determined how stale the installed version is. A null
     *   value does not block the prompt: Play already reported the update as
     *   available, so missing staleness data must not suppress it.
     * @param availableVersionCode the versionCode Play is offering.
     * @param lastPromptVersionCode the versionCode last prompted for (-1 if none).
     * @param lastPromptEpoch epoch-ms of the last prompt (0 if none).
     * @param skippedVersionCode versionCode the user explicitly tapped
     *   "Skip this version" on (-1 if none).
     * @param now current epoch-ms.
     */
    fun shouldStartFlow(
        stalenessDays: Int?,
        availableVersionCode: Int,
        lastPromptVersionCode: Int,
        lastPromptEpoch: Long,
        skippedVersionCode: Int,
        now: Long,
    ): Boolean {
        if (stalenessDays != null && stalenessDays < STALENESS_THRESHOLD_DAYS) return false
        if (availableVersionCode == skippedVersionCode) return false
        val sameVersionWithinCooldown =
            availableVersionCode == lastPromptVersionCode &&
                    now - lastPromptEpoch < COOLDOWN_MS
        return !sameVersionWithinCooldown
    }
}
