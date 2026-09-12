// Pins which permissions gate the 通知權限設定 link row on the Live Updates
// settings screen. The row exists so a user whose notification permission is
// off has somewhere to go; the cost of getting the set wrong is a permanent
// red row on a screen that works fine (too wide) or no row on a screen that
// cannot post anything (too narrow).

package org.ntust.app.tigerduck.liveactivity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.ntust.app.tigerduck.notification.AppPermission
import org.ntust.app.tigerduck.notification.PermissionState
import org.junit.Test

class LiveActivityPermissionsTest {

    private fun states(
        notifications: PermissionState = granted(AppPermission.NOTIFICATIONS),
        exactAlarm: PermissionState = granted(AppPermission.EXACT_ALARM),
        battery: PermissionState = granted(AppPermission.BATTERY_OPTIMIZATION),
        promoted: PermissionState = granted(AppPermission.PROMOTED_NOTIFICATIONS),
    ) = listOf(notifications, exactAlarm, battery, promoted)

    private fun granted(p: AppPermission) = PermissionState(p, granted = true, applicable = true)
    private fun denied(p: AppPermission) = PermissionState(p, granted = false, applicable = true)

    @Test
    fun `nothing is missing when every permission is granted`() {
        assertEquals(emptyList<AppPermission>(), LiveActivityPermissions.missing(states()))
    }

    @Test
    fun `a denied notification permission is missing`() {
        val missing = LiveActivityPermissions.missing(
            states(notifications = denied(AppPermission.NOTIFICATIONS))
        )

        assertEquals(listOf(AppPermission.NOTIFICATIONS), missing)
    }

    @Test
    fun `a denied status-bar chip is missing`() {
        val missing = LiveActivityPermissions.missing(
            states(promoted = denied(AppPermission.PROMOTED_NOTIFICATIONS))
        )

        assertEquals(listOf(AppPermission.PROMOTED_NOTIFICATIONS), missing)
    }

    @Test
    fun `a denied exact alarm is not missing - the boundary scheduler falls back to an inexact one`() {
        val missing = LiveActivityPermissions.missing(
            states(exactAlarm = denied(AppPermission.EXACT_ALARM))
        )

        assertEquals(emptyList<AppPermission>(), missing)
    }

    @Test
    fun `background restriction is not missing - nothing in the Live Update path reads it`() {
        val missing = LiveActivityPermissions.missing(
            states(battery = denied(AppPermission.BATTERY_OPTIMIZATION))
        )

        assertEquals(emptyList<AppPermission>(), missing)
    }

    @Test
    fun `a permission this OS version does not have is not missing`() {
        // PROMOTED_NOTIFICATIONS below API 36: SystemPermissions reports it
        // applicable = false, which the permission screen paints grey rather
        // than red. No row should appear for it here either.
        val missing = LiveActivityPermissions.missing(
            states(
                promoted = PermissionState(
                    AppPermission.PROMOTED_NOTIFICATIONS,
                    granted = false,
                    applicable = false,
                )
            )
        )

        assertEquals(emptyList<AppPermission>(), missing)
    }

    @Test
    fun `both required permissions denied at once are both missing`() {
        val missing = LiveActivityPermissions.missing(
            states(
                notifications = denied(AppPermission.NOTIFICATIONS),
                exactAlarm = denied(AppPermission.EXACT_ALARM),
                battery = denied(AppPermission.BATTERY_OPTIMIZATION),
                promoted = denied(AppPermission.PROMOTED_NOTIFICATIONS),
            )
        )

        assertEquals(
            listOf(AppPermission.NOTIFICATIONS, AppPermission.PROMOTED_NOTIFICATIONS),
            missing,
        )
    }

    @Test
    fun `the required set is exactly the two the Live Update depends on`() {
        assertTrue(
            AppPermission.EXACT_ALARM !in LiveActivityPermissions.REQUIRED &&
                AppPermission.BATTERY_OPTIMIZATION !in LiveActivityPermissions.REQUIRED
        )
        assertEquals(
            listOf(AppPermission.NOTIFICATIONS, AppPermission.PROMOTED_NOTIFICATIONS),
            LiveActivityPermissions.REQUIRED,
        )
    }
}
