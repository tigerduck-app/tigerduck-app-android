// 通知權限設定 (spec §6): the system-permissions section that used to sit at
// the bottom of the Live Updates settings screen, now its own destination —
// a third row in Settings › 通知, after 作業通知 and 即時更新. Moved, not
// copied: Live Activity settings no longer shows any of this.
//
// The screen owns the same three pieces LiveActivitySettingsScreen used to:
// the first-appearance POST_NOTIFICATIONS request, the ON_RESUME permission
// refresh, and the permission list itself — all driven straight off the
// SystemPermissions singleton rather than through a StateFlow, matching
// NotificationSetupScreen's shape (the closest existing precedent for a
// permission-only screen) rather than LiveActivitySettingsViewModel's, which
// only ever projected the same singleton into its own State for a screen
// that showed a lot of other settings besides.

package org.ntust.app.tigerduck.ui.screen.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import dagger.hilt.android.lifecycle.HiltViewModel
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.notification.SystemPermissions
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.NoTopBarInsets
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import javax.inject.Inject

@HiltViewModel
class NotificationPermissionSettingsViewModel @Inject constructor(
    val systemPermissions: SystemPermissions,
) : ViewModel()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationPermissionSettingsScreen(
    onBack: () -> Unit,
    viewModel: NotificationPermissionSettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val systemPermissions = viewModel.systemPermissions
    var permissions by remember { mutableStateOf(systemPermissions.states()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) systemPermissions.recordCurrentGrants()
        permissions = systemPermissions.states()
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Refresh permission rows each time the user returns to this screen, e.g.
    // after flipping a toggle in the system settings page we deep-linked to.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                systemPermissions.recordCurrentGrants()
                permissions = systemPermissions.states()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = NoTopBarInsets,
                title = { Text(stringResource(R.string.notification_permission_settings_nav_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                expandedHeight = SubSettingsBarHeight,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        ) {
            item { SectionHeader(stringResource(R.string.live_activity_settings_section_system_permissions)) }
            item {
                ContentCard {
                    Column {
                        permissions.forEachIndexed { idx, ps ->
                            if (idx > 0) HorizontalDivider()
                            PermissionRow(
                                state = ps,
                                onClick = {
                                    openPermissionPrompt(
                                        context = context,
                                        permission = ps.permission,
                                        systemPermissions = systemPermissions,
                                        askNotification = {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                            }
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.live_activity_settings_permissions_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
    }
}
