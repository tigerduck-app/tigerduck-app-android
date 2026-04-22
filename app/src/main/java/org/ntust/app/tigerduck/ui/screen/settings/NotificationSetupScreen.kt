package org.ntust.app.tigerduck.ui.screen.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import dagger.hilt.android.lifecycle.HiltViewModel
import org.ntust.app.tigerduck.notification.AppPermission
import org.ntust.app.tigerduck.notification.PermissionState
import org.ntust.app.tigerduck.notification.SystemPermissions
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import javax.inject.Inject

@HiltViewModel
class NotificationSetupViewModel @Inject constructor(
    val systemPermissions: SystemPermissions,
) : ViewModel()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSetupScreen(
    onDone: () -> Unit,
    viewModel: NotificationSetupViewModel = hiltViewModel(),
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("重新設定通知") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        NotificationSetupContent(
            systemPermissions = viewModel.systemPermissions,
            modifier = Modifier.padding(padding),
            finishLabel = "完成",
            onFinish = onDone,
        )
    }
}

/**
 * Shared walk-through block used both by 重新設定通知 and the onboarding
 * pager. Renders one row per applicable permission with a grant button and a
 * green tick once granted.
 */
@Composable
fun NotificationSetupContent(
    systemPermissions: SystemPermissions,
    modifier: Modifier = Modifier,
    finishLabel: String,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    var states by remember { mutableStateOf(systemPermissions.states()) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) systemPermissions.recordCurrentGrants()
        states = systemPermissions.states()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                systemPermissions.recordCurrentGrants()
                states = systemPermissions.states()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item {
            Text(
                "設定以下權限可確保作業提醒、即將上課、即時動態在 App 關閉時也能準時送達。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = ContentAlpha.SECONDARY),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        items(states) { s ->
            PermissionCard(
                state = s,
                onClick = {
                    when (s.permission) {
                        AppPermission.NOTIFICATIONS -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                !s.granted
                            ) {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                systemPermissions.settingsIntent(s.permission)?.let {
                                    it.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    context.startActivity(it)
                                }
                            }
                        }
                        else -> {
                            systemPermissions.settingsIntent(s.permission)?.let {
                                it.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                context.startActivity(it)
                            }
                        }
                    }
                },
            )
        }
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Button(
                    onClick = onFinish,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(finishLabel) }
            }
        }
    }
}

@Composable
private fun PermissionCard(state: PermissionState, onClick: () -> Unit) {
    ContentCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            !state.applicable -> Color(0xFFB0B0B0)
                            state.granted -> Color(0xFF34C759)
                            else -> Color(0xFFFF3B30)
                        }
                    )
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    SystemPermissions.displayName(state.permission),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    SystemPermissions.description(state.permission),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            when {
                !state.applicable -> Text(
                    "N/A",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                )
                state.granted -> Icon(
                    Icons.Filled.Check,
                    contentDescription = "已允許",
                    tint = Color(0xFF34C759),
                )
                else -> Button(
                    onClick = onClick,
                    colors = ButtonDefaults.buttonColors(),
                ) { Text("允許") }
            }
        }
    }
}
