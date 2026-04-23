package org.ntust.app.tigerduck.ui.component

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.ntust.app.tigerduck.notification.AppPermission
import org.ntust.app.tigerduck.notification.SystemPermissions
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

/**
 * Watches the device's notification / exact-alarm / battery-optimisation
 * permission states while any part of the app is on-screen. If a permission
 * that the user previously granted has been silently revoked (e.g. from the
 * system settings), surface a single unified warning with per-permission
 * "fix it" buttons and a "以後不再提醒" checkbox per row.
 */
@Composable
fun PermissionWarningDialogHost(systemPermissions: SystemPermissions) {
    var revoked by remember { mutableStateOf<List<AppPermission>>(emptyList()) }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) systemPermissions.recordCurrentGrants()
        revoked = systemPermissions.revokedSinceGrantUnmuted()
    }

    LaunchedEffect(Unit) {
        revoked = systemPermissions.revokedSinceGrantUnmuted()
    }

    // Re-check each time the activity returns to the foreground — that covers
    // the case where the user just came back from the system settings app.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                systemPermissions.recordCurrentGrants()
                revoked = systemPermissions.revokedSinceGrantUnmuted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (revoked.isEmpty()) return

    AlertDialog(
        onDismissRequest = { revoked = emptyList() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("偵測到通知權限已關閉", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "以下權限在您之前已允許，但現在已被關閉。關閉後對應的通知與即時動態可能無法正常顯示。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                revoked.forEachIndexed { idx, p ->
                    if (idx > 0) HorizontalDivider()
                    RevokedPermissionItem(
                        permission = p,
                        systemPermissions = systemPermissions,
                        onOpenSettings = {
                            if (p == AppPermission.NOTIFICATIONS &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                            ) {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                systemPermissions.openSettings(p)
                            }
                        },
                        onMute = {
                            systemPermissions.setMuted(p, true)
                            revoked = systemPermissions.revokedSinceGrantUnmuted()
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { revoked = emptyList() }) { Text("稍後再說") }
        },
    )
}

@Composable
private fun RevokedPermissionItem(
    permission: AppPermission,
    systemPermissions: SystemPermissions,
    onOpenSettings: () -> Unit,
    onMute: () -> Unit,
) {
    var muteChecked by remember(permission) { mutableStateOf(systemPermissions.isMuted(permission)) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            SystemPermissions.displayName(permission),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        )
        Text(
            SystemPermissions.description(permission),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            modifier = Modifier.padding(top = 2.dp),
        )
        TextButton(
            onClick = onOpenSettings,
            contentPadding = PaddingValues(horizontal = 0.dp),
        ) { Text("前往設定") }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    muteChecked = !muteChecked
                    if (muteChecked) onMute() else systemPermissions.setMuted(permission, false)
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = muteChecked,
                onCheckedChange = {
                    muteChecked = it
                    if (it) onMute() else systemPermissions.setMuted(permission, false)
                },
            )
            Text(
                "以後不再提醒此項",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
