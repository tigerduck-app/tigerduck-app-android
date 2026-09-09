// The system-permission rows at the bottom of the Live Activity settings
// screen, plus the routing that decides what tapping one does.
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.notification.AppPermission
import org.ntust.app.tigerduck.notification.SystemPermissions
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

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
                        else -> Color(0xFFFF3B30)
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
