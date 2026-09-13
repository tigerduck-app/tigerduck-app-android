// Which system permissions 即時更新 actually depends on, and the shown/hidden
// decision behind the link row the Live Updates settings screen puts above its
// settings when one of them is missing.
//
// Lives here rather than inside the Compose screen so the decision stays a pure
// function over PermissionState, testable without an instrumented run.

package org.ntust.app.tigerduck.liveactivity

import org.ntust.app.tigerduck.notification.AppPermission
import org.ntust.app.tigerduck.notification.PermissionState

object LiveActivityPermissions {

    /**
     * The permissions a Live Update genuinely needs, and only those:
     *
     * - [AppPermission.NOTIFICATIONS] — [LiveActivityNotifier.apply] drops the
     *   snapshot outright when POST_NOTIFICATIONS is denied, so nothing the
     *   screen can be set to produces anything.
     * - [AppPermission.PROMOTED_NOTIFICATIONS] — the status-bar chip: a
     *   separately grantable special access (API 36+) with its own system
     *   settings page. Without it the Live Update still posts, but only into
     *   the shade.
     *
     * EXACT_ALARM and BATTERY_OPTIMIZATION are deliberately absent.
     * [LiveActivityBoundaryScheduler] already falls back to an inexact alarm
     * when exact alarms are denied — the Live Update still shows, its scenario
     * boundary can just drift under Doze — and background restriction is an
     * app-wide background-work state nothing in the Live Update path consults.
     * Both keep their rows on 通知權限設定, alongside the reminders that do
     * depend on them.
     */
    val REQUIRED: List<AppPermission> = listOf(
        AppPermission.NOTIFICATIONS,
        AppPermission.PROMOTED_NOTIFICATIONS,
    )

    /**
     * The subset of [states] standing between the user and a working Live
     * Update: required, applicable on this OS version, and not granted.
     *
     * Empty means show nothing — no empty state, no always-present row.
     */
    fun missing(states: List<PermissionState>): List<AppPermission> =
        states.filter { it.permission in REQUIRED && it.applicable && !it.granted }
            .map { it.permission }
}
