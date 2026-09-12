// The system-permission rows shown on the notification-permission settings
// screen, plus the routing that decides what tapping one does. Lived on the
// Live Updates settings screen before spec §6 moved the whole
// system-permissions section out to its own screen — Live Updates keeps its
// display/sound/lock-screen/timing settings and shows no permission *states*
// at all now, only the one-line PermissionGapLinkRow below that points back
// here when something it needs is off.
//
// The routing is the part worth reading: for notifications on API 33+ we ask
// for the runtime permission first, because the settings deep link is a worse
// experience when the OS would still show the prompt. Android silently
// ignores the request once the user has denied twice, which is why the caller
// re-reads the permission states on every ON_RESUME instead of trusting the
// launcher callback.

package org.ntust.app.tigerduck.ui.screen.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.notification.AppPermission
import org.ntust.app.tigerduck.notification.SystemPermissions
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

/** iOS systemRed, the "off" dot on every permission surface in the app. */
private val NotGrantedDotColor = Color(0xFFFF3B30)

internal fun openPermissionPrompt(
    context: android.content.Context,
    permission: AppPermission,
    systemPermissions: SystemPermissions,
    askNotification: () -> Unit,
) {
    if (permission == AppPermission.NOTIFICATIONS &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        !systemPermissions.isGranted(AppPermission.NOTIFICATIONS)
    ) {
        // Runtime prompt first; if system decides not to show it (user denied
        // twice) Android silently ignores and we fall back to settings.
        askNotification()
        return
    }
    systemPermissions.openSettings(permission)
}

/**
 * The single row the Live Updates screen shows above its settings while a
 * permission a Live Update depends on is off — see
 * [org.ntust.app.tigerduck.liveactivity.LiveActivityPermissions] for which
 * ones and why.
 *
 * It is a way in to 通知權限設定, not a second permission list: the states,
 * the runtime prompt and the settings deep links all stay on that screen, so
 * there is only ever one place that asks for a permission. The red dot is
 * [PermissionRow]'s, the chevron [SettingsLinkRow]'s — the row reads as both
 * a warning and a destination because it is both.
 */
@Composable
internal fun PermissionGapLinkRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { role = Role.Button }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(NotGrantedDotColor)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.notification_permission_settings_nav_title),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            )
            Text(
                stringResource(R.string.permission_not_granted_tap_settings),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.DISABLED),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
internal fun PermissionRow(
    state: org.ntust.app.tigerduck.notification.PermissionState,
    onClick: () -> Unit,
) {
    val clickable = state.applicable && !state.granted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (clickable) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    when {
                        !state.applicable -> Color(0xFFB0B0B0)
                        state.granted -> Color(0xFF34C759)
                        else -> NotGrantedDotColor
                    }
                )
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(SystemPermissions.displayNameResId(state.permission)),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            )
            Text(
                when {
                    !state.applicable -> stringResource(R.string.permission_not_applicable)
                    state.granted -> stringResource(R.string.permission_granted)
                    else -> stringResource(R.string.permission_not_granted_tap_settings)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            )
        }
    }
}
