package org.ntust.app.tigerduck.data.model

/**
 * One pending "an update is available" prompt waiting to be surfaced. Lives in
 * `main/` so the play and fdroid `UpdateChecker` stubs can share the type — on
 * fdroid the holding flow is permanently null, so the dialog never mounts.
 *
 * Carries no version name: Play's `AppUpdateInfo` exposes only a versionCode,
 * which is what the Later / Skip gate keys on and is meaningless to show a
 * user, so the prompt names no version at all.
 */
data class PendingUpdate(
    val availableVersionCode: Int,
)
