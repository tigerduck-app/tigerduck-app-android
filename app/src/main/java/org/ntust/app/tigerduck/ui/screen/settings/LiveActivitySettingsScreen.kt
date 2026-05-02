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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.liveactivity.LiveActivityManager
import org.ntust.app.tigerduck.liveactivity.LiveActivityPreferences
import org.ntust.app.tigerduck.notification.AppPermission
import org.ntust.app.tigerduck.notification.SystemPermissions
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.ui.theme.tigerDuckSwitchColors
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
                title = { Text(stringResource(R.string.live_activity_channel_name)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(scaffoldPadding),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        ) {
            item {
                ContentCard {
                    SettingToggleRow(stringResource(R.string.live_activity_settings_enable), state.enabled) { viewModel.setEnabled(it) }
                }
            }
            item {
                Text(
                    stringResource(R.string.live_activity_settings_description),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }

            item { SectionHeader(stringResource(R.string.live_activity_settings_section_display_scenarios)) }
            item {
                ContentCard {
                    Column {
                        SettingToggleRow(
                            stringResource(R.string.live_activity_status_in_class), state.showInClass, enabled = state.enabled,
                        ) { viewModel.setShowInClass(it) }
                        HorizontalDivider()
                        SettingToggleRow(
                            stringResource(R.string.live_activity_status_class_preparing), state.showClassPreparing, enabled = state.enabled,
                        ) { viewModel.setShowClassPreparing(it) }
                        HorizontalDivider()
                        SettingToggleRow(
                            stringResource(R.string.home_section_upcoming_assignments), state.showAssignment, enabled = state.enabled,
                        ) { viewModel.setShowAssignment(it) }
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.live_activity_settings_section_sound)) }
            item {
                ContentCard {
                    Column {
                        SettingToggleRow(
                            stringResource(R.string.live_activity_settings_sound_in_class),
                            state.soundInClass,
                            enabled = state.enabled && state.showInClass,
                        ) { viewModel.setSoundInClass(it) }
                        HorizontalDivider()
                        SettingToggleRow(
                            stringResource(R.string.live_activity_settings_sound_class_preparing),
                            state.soundClassPreparing,
                            enabled = state.enabled && state.showClassPreparing,
                        ) { viewModel.setSoundClassPreparing(it) }
                        HorizontalDivider()
                        SettingToggleRow(
                            stringResource(R.string.live_activity_settings_sound_assignment),
                            state.soundAssignment,
                            enabled = state.enabled && state.showAssignment,
                        ) { viewModel.setSoundAssignment(it) }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.live_activity_settings_sound_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }

            item { SectionHeader(stringResource(R.string.live_activity_settings_section_lock_screen)) }
            item {
                ContentCard {
                    SettingToggleRow(
                        stringResource(R.string.live_activity_settings_show_on_lock_screen),
                        state.showOnLockScreen,
                        enabled = state.enabled,
                    ) { viewModel.setShowOnLockScreen(it) }
                }
            }
            item {
                Text(
                    stringResource(R.string.live_activity_settings_lock_screen_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }

            item { SectionHeader(stringResource(R.string.live_activity_settings_section_timing)) }
            item {
                ContentCard {
                    Column {
                        LeadTimeRow(
                            label = stringResource(R.string.live_activity_settings_assignment_warning),
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
                            label = stringResource(R.string.live_activity_status_class_preparing),
                            valueLabel = stringResource(R.string.live_activity_settings_minutes_label, state.classLeadMinutes),
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
                    stringResource(R.string.live_activity_settings_timing_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }

            item { SectionHeader(stringResource(R.string.live_activity_settings_section_system_permissions)) }
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
                    stringResource(R.string.live_activity_settings_permissions_hint),
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
                    ) { Text(stringResource(R.string.live_activity_settings_reset_defaults)) }
                }
            }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.live_activity_settings_reset_confirm_title)) },
            text = { Text(stringResource(R.string.live_activity_settings_reset_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetDefaults()
                    showResetConfirm = false
                }) { Text(stringResource(R.string.live_activity_settings_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (assignmentCustomOpen) {
        CustomHoursMinutesDialog(
            title = stringResource(R.string.live_activity_settings_custom_assignment_title),
            description = stringResource(R.string.live_activity_settings_custom_assignment_description),
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
            title = stringResource(R.string.live_activity_settings_custom_class_title),
            description = stringResource(R.string.live_activity_settings_custom_class_description),
            initialMinutes = state.classLeadMinutes,
            minMinutes = (LiveActivityPreferences.MIN_CLASS_LEAD_SEC / 60).toInt().coerceAtLeast(1),
            maxMinutes = (LiveActivityPreferences.MAX_CLASS_LEAD_SEC / 60).toInt(),
            unitHint = stringResource(
                R.string.live_activity_settings_custom_class_unit_hint,
                (LiveActivityPreferences.MAX_CLASS_LEAD_SEC / 60).toInt()
            ),
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

@Composable
private fun formatDuration(totalMinutes: Int): String {
    if (totalMinutes <= 0) return stringResource(R.string.live_activity_settings_minutes_label, 0)
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h == 0 -> stringResource(R.string.live_activity_settings_minutes_label, m)
        m == 0 -> stringResource(R.string.live_activity_settings_hours_label, h)
        else -> stringResource(R.string.live_activity_settings_hours_minutes_label, h, m)
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
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = tigerDuckSwitchColors(),
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
            ) { Text(stringResource(R.string.color_picker_custom), style = MaterialTheme.typography.labelMedium) }
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
    val parsed = text.toIntOrNull()?.takeIf { it in minMinutes..maxMinutes }
    val valid = parsed != null

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
                        onDone = { parsed?.let(onConfirm) }
                    ),
                )
                Text(
                    stringResource(R.string.live_activity_settings_range_minutes, minMinutes, maxMinutes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let(onConfirm) },
                enabled = valid,
            ) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
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
        minMinutes >= 60 -> stringResource(
            R.string.live_activity_settings_range_hours,
            minMinutes / 60,
            maxHours
        )
        else -> stringResource(
            R.string.live_activity_settings_range_min_to_hours,
            minMinutes,
            maxHours
        )
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
                        label = { Text(stringResource(R.string.live_activity_settings_hours_unit)) },
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
                        label = { Text(stringResource(R.string.live_activity_settings_minutes_unit)) },
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
            ) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
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
