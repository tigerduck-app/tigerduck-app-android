package org.ntust.app.tigerduck.ui.screen.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.liveactivity.LiveActivityManager
import org.ntust.app.tigerduck.liveactivity.LiveActivityPreferences
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import javax.inject.Inject

@HiltViewModel
class LiveActivitySettingsViewModel @Inject constructor(
    val prefs: LiveActivityPreferences,
    private val manager: LiveActivityManager,
) : ViewModel() {

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<State> = _state.asStateFlow()

    fun setEnabled(v: Boolean) { prefs.isEnabled = v; emit() }
    fun setShowInClass(v: Boolean) { prefs.showInClass = v; emit() }
    fun setShowClassPreparing(v: Boolean) { prefs.showClassPreparing = v; emit() }
    fun setShowAssignment(v: Boolean) { prefs.showAssignment = v; emit() }
    fun setAssignmentLeadHours(h: Int) {
        prefs.assignmentLeadTimeSec = (h.coerceIn(1, 8)).toLong() * 3600
        emit()
    }
    fun setClassLeadMinutes(m: Int) {
        prefs.classPreparingLeadTimeSec = (m.coerceIn(5, 60)).toLong() * 60
        emit()
    }

    fun resetDefaults() {
        prefs.resetToDefaults()
        emit()
    }

    private fun emit() {
        _state.value = snapshot()
        viewModelScope.launch { manager.refresh() }
    }

    private fun snapshot() = State(
        enabled = prefs.isEnabled,
        showInClass = prefs.showInClass,
        showClassPreparing = prefs.showClassPreparing,
        showAssignment = prefs.showAssignment,
        assignmentLeadHours = (prefs.assignmentLeadTimeSec / 3600).toInt().coerceIn(1, 8),
        classLeadMinutes = (prefs.classPreparingLeadTimeSec / 60).toInt().coerceIn(5, 60),
    )

    data class State(
        val enabled: Boolean,
        val showInClass: Boolean,
        val showClassPreparing: Boolean,
        val showAssignment: Boolean,
        val assignmentLeadHours: Int,
        val classLeadMinutes: Int,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveActivitySettingsScreen(
    onBack: () -> Unit,
    viewModel: LiveActivitySettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    var showResetConfirm by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op: notifier will silently skip if denied */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("即時動態（實驗性）") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(scaffoldPadding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                ContentCard {
                    SettingToggleRow("啟用即時動態", state.enabled) { viewModel.setEnabled(it) }
                }
            }
            item {
                Text(
                    "會在通知區顯示當前課程與作業倒數。需 App 在開啟才會即時更新",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }

            item { SectionHeader("顯示情境") }
            item {
                ContentCard {
                    Column {
                        SettingToggleRow(
                            "上課中", state.showInClass, enabled = state.enabled,
                        ) { viewModel.setShowInClass(it) }
                        HorizontalDivider()
                        SettingToggleRow(
                            "即將上課", state.showClassPreparing, enabled = state.enabled,
                        ) { viewModel.setShowClassPreparing(it) }
                        HorizontalDivider()
                        SettingToggleRow(
                            "作業", state.showAssignment, enabled = state.enabled,
                        ) { viewModel.setShowAssignment(it) }
                    }
                }
            }

            item { SectionHeader("顯示時機") }
            item {
                ContentCard {
                    Column {
                        SliderRow(
                            label = "作業警告",
                            valueLabel = "${state.assignmentLeadHours} 小時",
                            value = state.assignmentLeadHours.toFloat(),
                            range = 1f..8f,
                            steps = 6,
                            enabled = state.enabled,
                            onValueChange = { viewModel.setAssignmentLeadHours(it.toInt()) },
                        )
                        HorizontalDivider()
                        SliderRow(
                            label = "即將上課",
                            valueLabel = "${state.classLeadMinutes} 分鐘",
                            value = state.classLeadMinutes.toFloat(),
                            range = 5f..60f,
                            steps = 10,
                            enabled = state.enabled,
                            onValueChange = { viewModel.setClassLeadMinutes(it.toInt()) },
                        )
                    }
                }
            }

            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                ) {
                    OutlinedButton(
                        onClick = { showResetConfirm = true },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("重置為預設值") }
                }
            }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("重置所有即時動態設定？") },
            text = { Text("所有情境與顯示時機將還原為預設值。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetDefaults()
                    showResetConfirm = false
                }) { Text("重置") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SettingToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) Color.Unspecified
                   else MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.DISABLED),
        )
        val isDark = TigerDuckTheme.isDarkMode
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = if (isDark) Color(0xFF39393D) else Color(0xFFE9E9EB),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                valueLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                fontWeight = FontWeight.Medium,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            enabled = enabled,
        )
    }
}
