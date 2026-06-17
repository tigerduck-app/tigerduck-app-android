package org.ntust.app.tigerduck.data.model

/**
 * One pending "an update is available" prompt waiting to be surfaced. Lives in
 * `main/` so the play and fdroid `UpdateChecker` stubs can share the type — on
 * fdroid the holding flow is permanently null, so the dialog never mounts.
 *
 * [availableVersionName] is best-effort: Play's `AppUpdateInfo` does not
 * expose a version *name*, only a versionCode, so the UI falls back to
 * displaying the versionCode when the name is unknown. Either reads as
 * "obviously newer" to the user, which is all the prompt needs to convey.
 */
data class PendingUpdate(
    val availableVersionCode: Int,
    val availableVersionName: String? = null,
) {
    /** Human-readable string for the dialog body. */
    val displayVersion: String get() = availableVersionName ?: availableVersionCode.toString()
}
