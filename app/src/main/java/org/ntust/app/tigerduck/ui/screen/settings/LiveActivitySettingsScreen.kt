package org.ntust.app.tigerduck.ui.screen.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.liveactivity.LiveActivityManager
import org.ntust.app.tigerduck.liveactivity.LiveActivityPreferences
import org.ntust.app.tigerduck.notification.AppPermission
import org.ntust.app.tigerduck.notification.SystemPermissions
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import javax.inject.Inject

@HiltViewModel
class LiveActivitySettingsViewModel @Inject constructor(
    val prefs: LiveActivityPreferences,
    val systemPermissions: SystemPermissions,
    private val manager: LiveActivityManager,
) : ViewModel() {

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<State> = _state.asStateFlow()

    fun setEnabled(v: Boolean) { prefs.isEnabled = v; emit() }
    fun setShowInClass(v: Boolean) { prefs.showInClass = v; emit() }
    fun setShowClassPreparing(v: Boolean) { prefs.showClassPreparing = v; emit() }
    fun setShowAssignment(v: Boolean) { prefs.showAssignment = v; emit() }
    fun setShowOnLockScreen(v: Boolean) { prefs.showOnLockScreen = v; emit() }
    fun setSoundInClass(v: Boolean) { prefs.soundInClass = v; emit() }
    fun setSoundClassPreparing(v: Boolean) { prefs.soundClassPreparing = v; emit() }
    fun setSoundAssignment(v: Boolean) { prefs.soundAssignment = v; emit() }

    fun setAssignmentLeadMinutes(minutes: Int) {
        val floor = (LiveActivityPreferences.MIN_ASSIGNMENT_LEAD_SEC / 60).toInt()
        val ceiling = (LiveActivityPreferences.MAX_ASSIGNMENT_LEAD_SEC / 60).toInt()
        prefs.assignmentLeadTimeSec = minutes.coerceIn(floor, ceiling).toLong() * 60
        emit()
    }

    fun setClassLeadMinutes(m: Int) {
        val floor = (LiveActivityPreferences.MIN_CLASS_LEAD_SEC / 60).toInt().coerceAtLeast(1)
        val ceiling = (LiveActivityPreferences.MAX_CLASS_LEAD_SEC / 60).toInt()
        prefs.classPreparingLeadTimeSec = m.coerceIn(floor, ceiling).toLong() * 60
        emit()
    }

    fun resetDefaults() {
        prefs.resetToDefaults()
        emit()
    }

    /** Called when the screen resumes so the permission rows reflect reality. */
    fun refreshPermissions() {
        _state.value = _state.value.copy(permissions = systemPermissions.states())
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
        showOnLockScreen = prefs.showOnLockScreen,
        soundInClass = prefs.soundInClass,
        soundClassPreparing = prefs.soundClassPreparing,
        soundAssignment = prefs.soundAssignment,
        assignmentLeadMinutes = (prefs.assignmentLeadTimeSec / 60).toInt(),
        classLeadMinutes = (prefs.classPreparingLeadTimeSec / 60).toInt(),
        permissions = systemPermissions.states(),
    )

    data class State(
        val enabled: Boolean,
        val showInClass: Boolean,
        val showClassPreparing: Boolean,
        val showAssignment: Boolean,
        val showOnLockScreen: Boolean,
        val soundInClass: Boolean,
        val soundClassPreparing: Boolean,
        val soundAssignment: Boolean,
        val assignmentLeadMinutes: Int,
        val classLeadMinutes: Int,
        val permissions: List<org.ntust.app.tigerduck.notification.PermissionState>,
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
    var assignmentCustomOpen by remember { mutableStateOf(false) }
    var classCustomOpen by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.systemPermissions.recordCurrentGrants()
        viewModel.refreshPermissions()
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
                viewModel.systemPermissions.recordCurrentGrants()
                viewModel.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("即時動態") },
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
                    "即時動態會在通知區顯示當前課程與倒數。「上課中」與「即將上課」僅在 App 開啟時會即時更新；「作業警告」即使 App 已關閉也會準時提醒。",
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

            item { SectionHeader("提示音") }
            item {
                ContentCard {
                    Column {
                        SettingToggleRow(
                            "上課中 發出提示音",
                            state.soundInClass,
                            enabled = state.enabled && state.showInClass,
                        ) { viewModel.setSoundInClass(it) }
                        HorizontalDivider()
                        SettingToggleRow(
                            "即將上課 發出提示音",
                            state.soundClassPreparing,
                            enabled = state.enabled && state.showClassPreparing,
                        ) { viewModel.setSoundClassPreparing(it) }
                        HorizontalDivider()
                        SettingToggleRow(
                            "作業警告 發出提示音",
                            state.soundAssignment,
                            enabled = state.enabled && state.showAssignment,
                        ) { viewModel.setSoundAssignment(it) }
                    }
                }
            }
            item {
                Text(
                    "只有在進入該情境的那一刻會響一次，之後的倒數更新不會再發聲。若要進一步調整音量或勿擾模式，請至系統通知設定中調整「即時動態」頻道。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }

            item { SectionHeader("鎖定畫面") }
            item {
                ContentCard {
                    SettingToggleRow(
                        "在鎖定畫面顯示詳細內容",
                        state.showOnLockScreen,
                        enabled = state.enabled,
                    ) { viewModel.setShowOnLockScreen(it) }
                }
            }
            item {
                Text(
                    "關閉時鎖定畫面只會顯示「通知已隱藏」類的佔位文字。若開啟後仍無效果，請至系統通知設定中允許在鎖定畫面顯示此類通知。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }

            item { SectionHeader("顯示時機") }
            item {
                ContentCard {
                    Column {
                        LeadTimeRow(
                            label = "作業警告",
                            valueLabel = formatDuration(state.assignmentLeadMinutes),
                            value = state.assignmentLeadMinutes.toFloat(),
                            range = 60f..(8f * 60f),
                            steps = 0,
                            enabled = state.enabled,
                            onValueChange = { viewModel.setAssignmentLeadMinutes(it.toInt()) },
                            onCustomClick = { assignmentCustomOpen = true },
                        )
                        HorizontalDivider()
                        LeadTimeRow(
                            label = "即將上課",
                            valueLabel = "${state.classLeadMinutes} 分鐘",
                            value = state.classLeadMinutes.toFloat(),
                            range = 5f..60f,
                            steps = 0,
                            enabled = state.enabled,
                            onValueChange = { viewModel.setClassLeadMinutes(it.toInt()) },
                            onCustomClick = { classCustomOpen = true },
                        )
                    }
                }
            }
            item {
                Text(
                    "滑桿提供常用範圍；如需超出範圍的值（例如 1 天前提醒作業）請點「自訂」。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }

            item { SectionHeader("系統權限") }
            item {
                ContentCard {
                    Column {
                        state.permissions.forEachIndexed { idx, ps ->
                            if (idx > 0) HorizontalDivider()
                            PermissionRow(
                                state = ps,
                                onClick = {
                                    openPermissionPrompt(
                                        context = context,
                                        permission = ps.permission,
                                        systemPermissions = viewModel.systemPermissions,
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
                    "通知與精確鬧鐘為發送提醒的必要條件；關閉省電限制可確保 App 關閉或背景時仍能準時提醒。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
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

    if (assignmentCustomOpen) {
        CustomHoursMinutesDialog(
            title = "自訂作業警告時間",
            description = "App 會在到期前這麼久開始顯示提醒。",
            initialMinutes = state.assignmentLeadMinutes,
            minMinutes = (LiveActivityPreferences.MIN_ASSIGNMENT_LEAD_SEC / 60).toInt(),
            maxMinutes = (LiveActivityPreferences.MAX_ASSIGNMENT_LEAD_SEC / 60).toInt(),
            onConfirm = {
                viewModel.setAssignmentLeadMinutes(it)
                assignmentCustomOpen = false
            },
            onDismiss = { assignmentCustomOpen = false },
        )
    }
    if (classCustomOpen) {
        CustomMinutesDialog(
            title = "自訂即將上課時間",
            description = "App 會在上課前這麼久開始提醒。",
            initialMinutes = state.classLeadMinutes,
            minMinutes = (LiveActivityPreferences.MIN_CLASS_LEAD_SEC / 60).toInt().coerceAtLeast(1),
            maxMinutes = (LiveActivityPreferences.MAX_CLASS_LEAD_SEC / 60).toInt(),
            unitHint = "分鐘（最多 ${(LiveActivityPreferences.MAX_CLASS_LEAD_SEC / 60).toInt()} 分鐘）",
            onConfirm = {
                viewModel.setClassLeadMinutes(it)
                classCustomOpen = false
            },
            onDismiss = { classCustomOpen = false },
        )
    }
}

private fun openPermissionPrompt(
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

private fun formatDuration(totalMinutes: Int): String {
    if (totalMinutes <= 0) return "0 分鐘"
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h == 0 -> "$m 分鐘"
        m == 0 -> "$h 小時"
        else -> "$h 小時 $m 分"
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
private fun LeadTimeRow(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onCustomClick: () -> Unit,
) {
    // Clamp the slider knob within its visible range even when the user has
    // set a value outside the common range via the 自訂 dialog.
    val displayValue = value.coerceIn(range.start, range.endInclusive)
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
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onCustomClick,
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) { Text("自訂", style = MaterialTheme.typography.labelMedium) }
        }
        Slider(
            value = displayValue,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            enabled = enabled,
        )
    }
}

@Composable
private fun CustomMinutesDialog(
    title: String,
    description: String,
    initialMinutes: Int,
    minMinutes: Int,
    maxMinutes: Int,
    unitHint: String,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialMinutes.toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in minMinutes..maxMinutes

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(description, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { new ->
                        text = new.filter { it.isDigit() }.take(5)
                    },
                    label = { Text(unitHint) },
                    singleLine = true,
                    isError = text.isNotEmpty() && !valid,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (valid && parsed != null) onConfirm(parsed) }
                    ),
                )
                Text(
                    "範圍：$minMinutes – $maxMinutes 分鐘",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (valid && parsed != null) onConfirm(parsed) },
                enabled = valid,
            ) { Text("確定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun CustomHoursMinutesDialog(
    title: String,
    description: String,
    initialMinutes: Int,
    minMinutes: Int,
    maxMinutes: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var hoursText by remember { mutableStateOf((initialMinutes / 60).toString()) }
    var minutesText by remember { mutableStateOf((initialMinutes % 60).toString()) }

    val hours = hoursText.toIntOrNull() ?: -1
    val minutes = minutesText.toIntOrNull() ?: -1
    val total = if (hours >= 0 && minutes in 0..59) hours * 60 + minutes else -1
    val valid = total in minMinutes..maxMinutes

    val maxHours = maxMinutes / 60
    val rangeHint = when {
        minMinutes >= 60 -> "範圍：${minMinutes / 60} 小時 – $maxHours 小時"
        else -> "範圍：$minMinutes 分 – $maxHours 小時"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(description, style = MaterialTheme.typography.bodyMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = hoursText,
                        onValueChange = { new ->
                            hoursText = new.filter { it.isDigit() }.take(4)
                        },
                        label = { Text("小時") },
                        singleLine = true,
                        isError = !valid && hoursText.isNotEmpty(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = minutesText,
                        onValueChange = { new ->
                            minutesText = new.filter { it.isDigit() }.take(2)
                        },
                        label = { Text("分鐘") },
                        singleLine = true,
                        isError = !valid && minutesText.isNotEmpty(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { if (valid) onConfirm(total) }
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    rangeHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (valid) onConfirm(total) },
                enabled = valid,
            ) { Text("確定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun PermissionRow(
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
                SystemPermissions.displayName(state.permission),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            )
            Text(
                when {
                    !state.applicable -> "此系統版本無此設定"
                    state.granted -> "已允許"
                    else -> "未允許 · 點擊前往設定"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            )
        }
    }
}
