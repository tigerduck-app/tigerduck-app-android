package org.ntust.app.tigerduck.ui.screen.settings

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.push.PushDiagnostic
import org.ntust.app.tigerduck.push.PushIdentity
import org.ntust.app.tigerduck.push.PushRegistrationService
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/**
 * Mirrors iOS PushServerSettingsView: master push toggle (defaults ON),
 * the status card (permission + device registration + last registration
 * + latest error), and the device ID (operator support handle).
 *
 * The toggle wraps [PushRegistrationService.updateServerPushOptOut] so
 * flipping it patches `device_registrations.server_push_enabled` on the
 * backend immediately — same flow the SubscriptionSettings screen used
 * before the row was moved here.
 */
@HiltViewModel
class ServerPushViewModel @Inject constructor(
    private val pushRegistration: PushRegistrationService,
    identity: PushIdentity,
) : ViewModel() {

    data class State(
        val serverPushOn: Boolean = true,
        val diagnostic: PushDiagnostic = PushDiagnostic(false, false, null, null, null),
        val deviceId: String = "",
        val isSyncing: Boolean = false,
    )

    private val _state = MutableStateFlow(
        State(
            serverPushOn = !pushRegistration.isServerPushOptedOut(),
            deviceId = identity.deviceId(),
        )
    )
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        pushRegistration.diagnostic
            .onEach { d -> _state.update { it.copy(diagnostic = d) } }
            .launchIn(viewModelScope)
    }

    fun setServerPushOn(isOn: Boolean) {
        _state.update { it.copy(serverPushOn = isOn) }
        viewModelScope.launch {
            pushRegistration.updateServerPushOptOut(optOut = !isOn)
        }
    }

    fun syncNow() {
        if (_state.value.isSyncing) return
        _state.update { it.copy(isSyncing = true) }
        viewModelScope.launch {
            try {
                pushRegistration.syncNow()
            } finally {
                _state.update { it.copy(isSyncing = false) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerPushScreen(
    onBack: () -> Unit,
    viewModel: ServerPushViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.push_server_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    ServerPushToggleCard(
                        checked = state.serverPushOn,
                        onCheckedChange = viewModel::setServerPushOn,
                    )
                }
                item {
                    PushStatusCard(
                        diagnostic = state.diagnostic,
                        isSyncing = state.isSyncing,
                        onSyncNow = viewModel::syncNow,
                    )
                }
                item {
                    DeviceIdCard(state.deviceId)
                }
            }
        }
    }
}

@Composable
private fun ServerPushToggleCard(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ContentCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_server_push_label),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.settings_server_push_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun PushStatusCard(
    diagnostic: PushDiagnostic,
    isSyncing: Boolean,
    onSyncNow: () -> Unit,
) {
    val context = LocalContext.current
    fun checkNotificationGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true

    var permissionGranted by remember { mutableStateOf(checkNotificationGranted()) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> permissionGranted = granted }

    // Re-check on ON_RESUME so revoking POST_NOTIFICATIONS in system Settings
    // and returning here reflects the current grant, not the stale value
    // captured on first composition.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionGranted = checkNotificationGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ContentCard {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.push_server_status_section),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(8.dp))
            StatusRow(
                label = stringResource(R.string.bulletin_push_status_label),
                ok = permissionGranted,
                okText = stringResource(R.string.permission_granted),
                badText = stringResource(R.string.bulletin_push_status_denied),
            )
            StatusRow(
                label = stringResource(R.string.push_server_status_device_registration),
                ok = diagnostic.isRegistered,
                okText = stringResource(R.string.bulletin_push_status_registration_done),
                badText = if (diagnostic.hasFcmToken) {
                    stringResource(R.string.push_server_status_waiting_token)
                } else {
                    stringResource(R.string.bulletin_push_status_registration_pending)
                },
            )
            diagnostic.lastRegistrationAt?.let { ts ->
                Spacer(Modifier.height(4.dp))
                LabeledText(
                    label = stringResource(R.string.push_server_last_registration),
                    value = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(ts)),
                )
            }
            diagnostic.lastSyncAt?.let { ts ->
                Spacer(Modifier.height(4.dp))
                LabeledText(
                    label = stringResource(R.string.push_server_last_sync),
                    value = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(ts)),
                )
            }
            diagnostic.lastError?.let { msg ->
                Spacer(Modifier.height(4.dp))
                LabeledText(
                    label = stringResource(R.string.push_server_latest_error),
                    value = msg,
                    valueColor = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onSyncNow,
                enabled = !isSyncing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(R.string.push_server_sync_now_action))
                }
            }

            if (!permissionGranted) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            openAppSettings(context)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.bulletin_push_reopen_settings))
                }
            }
        }
    }
}

@Composable
private fun DeviceIdCard(deviceId: String) {
    val context = LocalContext.current
    val copiedMessage = stringResource(R.string.device_id_copied)
    ContentCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("device_id", deviceId))
                    // Android 13+ shows a system-level "Copied" chip on its own,
                    // so suppress the toast there to avoid double feedback.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                    }
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.device_id_label),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = deviceId.ifBlank { "—" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Filled.ContentCopy,
                contentDescription = stringResource(R.string.device_id_copy_action),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, okText: String, badText: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Icon(
            imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.WarningAmber,
            contentDescription = null,
            tint = if (ok) Color(0xFF34C759) else Color(0xFFFF9500),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            text = if (ok) okText else badText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun LabeledText(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = valueColor)
    }
}

@Composable
private fun ContentCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) { content() }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    runCatching { context.startActivity(intent) }
}
