package org.ntust.app.tigerduck.update

import java.util.concurrent.TimeUnit

/**
 * Pure gating logic for the in-app update prompt. Kept Android-free so it is
 * unit-testable and so the flavor-specific [UpdateChecker] (play) only has to
 * supply raw values from `AppUpdateInfo`.
 *
 * "Don't nag" policy (issue #89): only prompt for updates that are genuinely
 * stale, and never re-prompt for the same available version inside a cooldown
 * window — some users intentionally stay on an older version.
 */
object UpdatePromptGate {

    /** Minimum days the installed version must be behind before prompting. */
    const val STALENESS_THRESHOLD_DAYS = 3

    /** Re-prompt cooldown for the same available versionCode. */
    val COOLDOWN_MS: Long = TimeUnit.DAYS.toMillis(7)

    /**
     * @param stalenessDays Play's `clientVersionStalenessDays()` — null when
     *   Play has not yet determined how stale the installed version is.
     * @param availableVersionCode the versionCode Play is offering.
     * @param lastPromptVersionCode the versionCode last prompted for (-1 if none).
     * @param lastPromptEpoch epoch-ms of the last prompt (0 if none).
     * @param now current epoch-ms.
     */
    fun shouldStartFlow(
        stalenessDays: Int?,
        availableVersionCode: Int,
        lastPromptVersionCode: Int,
        lastPromptEpoch: Long,
        now: Long,
    ): Boolean {
        if ((stalenessDays ?: 0) < STALENESS_THRESHOLD_DAYS) return false
        val sameVersionWithinCooldown =
            availableVersionCode == lastPromptVersionCode &&
                now - lastPromptEpoch < COOLDOWN_MS
        return !sameVersionWithinCooldown
    }
}
