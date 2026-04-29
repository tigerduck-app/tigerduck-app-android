package org.ntust.app.tigerduck.ui.screen.settings

import android.content.Intent
import org.ntust.app.tigerduck.BuildConfig
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.PageHeader
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateToTabEditor: () -> Unit = {},
    onNavigateToLanguagePicker: () -> Unit = {},
    onNavigateToLiveActivity: () -> Unit = {},
    onNavigateToNotificationSetup: () -> Unit = {},
    onNavigateToSourceCode: () -> Unit = {},
) {
    val context = LocalContext.current
    val isNtustLoggingIn by viewModel.isNtustLoggingIn.collectAsState()
    val ntustLoginError by viewModel.ntustLoginError.collectAsState()
    val libIsLoggingIn by viewModel.libIsLoggingIn.collectAsState()
    val libLoginError by viewModel.libLoginError.collectAsState()
    val isNtustLoggedIn by viewModel.isNtustLoggedIn.collectAsState()
    val isLibraryLoggedIn by viewModel.isLibraryLoggedIn.collectAsState()

    var showNtustLoginSheet by remember { mutableStateOf(false) }
    var showLibraryLoginSheet by remember { mutableStateOf(false) }

    // Auto-dismiss dialogs when login succeeds
    LaunchedEffect(isNtustLoggedIn) {
        if (isNtustLoggedIn) showNtustLoginSheet = false
    }
    LaunchedEffect(isLibraryLoggedIn) {
        if (isLibraryLoggedIn) showLibraryLoginSheet = false
    }

    val accentColorHex = viewModel.appState.accentColorHex
    val showAbsoluteTime = viewModel.appState.showAbsoluteAssignmentTime
    val browserPreference = viewModel.appState.browserPreference
    val invertSlider = viewModel.appState.invertSliderDirection
    val useEnglishCourseAbbreviation = viewModel.appState.useEnglishCourseAbbreviation
    val useEnglishClassroomAbbreviation = viewModel.appState.useEnglishClassroomAbbreviation
    val classroomMandarinDisplay = viewModel.appState.classroomMandarinDisplay
    val notifyAssignments = viewModel.appState.notifyAssignments
    val libraryEnabled = viewModel.appState.libraryFeatureEnabled
    val themeMode = viewModel.appState.themeMode
    val appLanguage = viewModel.appState.appLanguage
    val shouldShowEnglishAbbreviationToggle = AppLanguageManager.isCourseApiEnglish(appLanguage)

    var showLibraryWarning by remember { mutableStateOf(false) }
    var showResetColorsConfirm by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val appVersion = remember { BuildConfig.VERSION_NAME }

    // Show network error as snackbar
    LaunchedEffect(ntustLoginError) {
        val error = ntustLoginError ?: return@LaunchedEffect
        if (
            error.contains("連線") ||
            error.contains("網路") ||
            error.contains("network", ignoreCase = true) ||
            error.contains("connection", ignoreCase = true)
        ) {
            snackbarHostState.showSnackbar(error)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { scaffoldPadding ->
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(scaffoldPadding),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            PageHeader(title = stringResource(R.string.feature_settings))
        }

        // MARK: Account section
        item { SectionHeader(stringResource(R.string.settings_section_account)) }
        item {
            ContentCard {
                Column {
                    AccountRow(
                        title = stringResource(R.string.settings_account_ntust_system),
                        isLoggedIn = isNtustLoggedIn,
                        subtitle = if (isNtustLoggedIn) viewModel.ntustStudentId else null,
                        isLoggingIn = isNtustLoggingIn,
                        onLogin = { showNtustLoginSheet = true },
                        onLogout = { viewModel.logoutNtust() },
                    )

                    if (libraryEnabled) {
                        HorizontalDivider()
                        val expiryMs = viewModel.libraryTokenExpiry
                        val expirySubtitle = if (isLibraryLoggedIn && expiryMs > 0) {
                            val fmt = SimpleDateFormat("yyyy/MM/dd", Locale.TAIWAN).apply {
                                timeZone = org.ntust.app.tigerduck.AppConstants.TAIPEI_TZ
                            }
                            stringResource(R.string.settings_token_valid_until, fmt.format(Date(expiryMs)))
                        } else null
                        AccountRow(
                            title = stringResource(R.string.settings_account_library_system),
                            isLoggedIn = isLibraryLoggedIn,
                            subtitle = if (isLibraryLoggedIn) viewModel.libraryUsername else null,
                            extraSubtitle = expirySubtitle,
                            isLoggingIn = libIsLoggingIn,
                            onLogin = { showLibraryLoginSheet = true },
                            onLogout = { viewModel.logoutLibrary() },
                        )
                    }
                }
            }
        }

        // MARK: Custom
        item { SectionHeader(stringResource(R.string.settings_section_custom)) }
        item {
            ContentCard {
                Column {
                    SettingsLinkRow(stringResource(R.string.tab_editor_title)) { onNavigateToTabEditor() }
                    HorizontalDivider()
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(stringResource(R.string.settings_accent_color), style = MaterialTheme.typography.bodyMedium)
                        // Show the mode-appropriate display color but always
                        // persist the canonical (light) hex so the pair swaps
                        // when the user toggles 顏色主題.
                        val accentPaletteDisplay = if (TigerDuckTheme.isDarkMode) {
                            AppPreferences.themeColorsDark
                        } else {
                            AppPreferences.themeColors
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            accentPaletteDisplay.forEachIndexed { idx, (_, displayHex) ->
                                val canonicalHex = AppPreferences.themeColors[idx].second
                                val color = Color(0xFF000000 or displayHex.toLong())
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .clickable { viewModel.appState.accentColorHex = canonicalHex },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (accentColorHex == canonicalHex) {
                                        Text("\u2713", color = Color.White, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // MARK: Display
        item { SectionHeader(stringResource(R.string.settings_section_display)) }
        item {
            ContentCard {
                Column {
                    SettingsToggleRow(stringResource(R.string.settings_show_absolute_assignment_time), showAbsoluteTime) {
                        viewModel.appState.showAbsoluteAssignmentTime = it
                    }
                    HorizontalDivider()
                    // Link opening method
                    SettingsPickerRow(
                        label = stringResource(R.string.settings_link_opening_method),
                        value = if (browserPreference == "inApp") {
                            stringResource(R.string.settings_browser_in_app)
                        } else {
                            stringResource(R.string.settings_browser_system_default)
                        },
                        options = listOf(
                            "system" to stringResource(R.string.settings_browser_system_default),
                            "inApp" to stringResource(R.string.settings_browser_in_app)
                        ),
                        selectedKey = browserPreference,
                        onSelect = { viewModel.appState.browserPreference = it }
                    )
                    HorizontalDivider()
                    SettingsToggleRow(stringResource(R.string.settings_invert_slider_direction), invertSlider) {
                        viewModel.appState.invertSliderDirection = it
                    }
                    HorizontalDivider()
                    SettingsLinkRowWithValue(
                        label = stringResource(R.string.settings_language),
                        value = run {
                            val normalized = AppLanguageManager.normalize(appLanguage)
                            if (normalized == AppLanguageManager.SYSTEM) {
                                stringResource(R.string.settings_language_follow_system)
                            } else {
                                val locale = Locale.forLanguageTag(normalized)
                                locale.getDisplayName(locale).ifBlank { normalized }
                            }
                        },
                        onClick = onNavigateToLanguagePicker,
                    )
                    if (shouldShowEnglishAbbreviationToggle) {
                        HorizontalDivider()
                        SettingsToggleRow(
                            stringResource(R.string.settings_use_english_course_abbreviation),
                            useEnglishCourseAbbreviation
                        ) {
                            viewModel.appState.useEnglishCourseAbbreviation = it
                        }
                        HorizontalDivider()
                        SettingsToggleRow(
                            stringResource(R.string.settings_use_english_classroom_abbreviation),
                            useEnglishClassroomAbbreviation
                        ) {
                            viewModel.appState.useEnglishClassroomAbbreviation = it
                        }
                        if (useEnglishClassroomAbbreviation) {
                            HorizontalDivider()
                            SettingsPickerRow(
                                label = stringResource(R.string.settings_classroom_mandarin_display),
                                value = when (classroomMandarinDisplay) {
                                    AppPreferences.CLASSROOM_MANDARIN_DISPLAY_PINYIN ->
                                        stringResource(R.string.settings_classroom_mandarin_display_pinyin)
                                    AppPreferences.CLASSROOM_MANDARIN_DISPLAY_TRANSLATED ->
                                        stringResource(R.string.settings_classroom_mandarin_display_translated)
                                    else ->
                                        stringResource(R.string.settings_classroom_mandarin_display_original)
                                },
                                options = listOf(
                                    AppPreferences.CLASSROOM_MANDARIN_DISPLAY_ORIGINAL to
                                        stringResource(R.string.settings_classroom_mandarin_display_original),
                                    AppPreferences.CLASSROOM_MANDARIN_DISPLAY_PINYIN to
                                        stringResource(R.string.settings_classroom_mandarin_display_pinyin),
                                    AppPreferences.CLASSROOM_MANDARIN_DISPLAY_TRANSLATED to
                                        stringResource(R.string.settings_classroom_mandarin_display_translated),
                                ),
                                selectedKey = classroomMandarinDisplay,
                                onSelect = { viewModel.appState.classroomMandarinDisplay = it }
                            )
                        }
                    }
                }
            }
        }

        // MARK: Notifications
        item { SectionHeader(stringResource(R.string.settings_section_notifications)) }
        item {
            ContentCard {
                Column {
                    SettingsToggleRow(stringResource(R.string.settings_assignment_due_reminder), notifyAssignments) {
                        viewModel.appState.notifyAssignments = it
                        if (!it) viewModel.cancelAllAssignmentNotifications()
                    }
                    HorizontalDivider()
                    SettingsLinkRow(stringResource(R.string.live_activity_channel_name)) { onNavigateToLiveActivity() }
                    HorizontalDivider()
                    SettingsLinkRow(stringResource(R.string.notification_setup_title)) { onNavigateToNotificationSetup() }
                }
            }
        }

        // MARK: Other features
        item { SectionHeader(stringResource(R.string.settings_section_other_features)) }
        item {
            ContentCard {
                Column {
                SettingsToggleRow(stringResource(R.string.settings_library_related_features), libraryEnabled) { enabled ->
                    if (enabled) {
                        showLibraryWarning = true
                    } else {
                        viewModel.appState.libraryFeatureEnabled = false
                        viewModel.appState.configuredTabs =
                            viewModel.appState.configuredTabs.filter { !it.isLibraryRelated }
                    }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SettingRowHeight)
                        .clickable { showResetColorsConfirm = true }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.settings_reset_course_colors), style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider()
                SettingsPickerRow(
                    label = stringResource(R.string.settings_color_theme),
                    value = when (themeMode) {
                        "dark" -> stringResource(R.string.settings_theme_dark)
                        "light" -> stringResource(R.string.settings_theme_light)
                        else -> stringResource(R.string.settings_theme_system)
                    },
                    options = listOf(
                        "system" to stringResource(R.string.settings_theme_system),
                        "dark" to stringResource(R.string.settings_theme_dark),
                        "light" to stringResource(R.string.settings_theme_light)
                    ),
                    selectedKey = themeMode,
                    onSelect = { viewModel.appState.themeMode = it }
                )
                } // Column
            }
        }

        // MARK: About
        item { SectionHeader(stringResource(R.string.settings_section_about)) }
        item {
            ContentCard {
                Column {
                    SettingsRow(stringResource(R.string.settings_version), appVersion)
                    HorizontalDivider()
                    SettingsLinkRow(stringResource(R.string.settings_feedback_bug_report)) {
                        openUrl(context, "https://github.com/tigerduck-app/tigerduck-app-android/issues", browserPreference)
                    }
                    HorizontalDivider()
                    SettingsLinkRow(stringResource(R.string.settings_privacy_policy)) {
                        openUrl(context, "https://app.ntust.org/tigerduck/privacy", browserPreference)
                    }
                    HorizontalDivider()
                    SettingsLinkRow(stringResource(R.string.settings_open_source_licenses)) {
                        openUrl(context, "https://github.com/tigerduck-app/tigerduck-app-android/blob/main/LICENSE", browserPreference)
                    }
                    HorizontalDivider()
                    SettingsLinkRow(stringResource(R.string.settings_view_source_code)) { onNavigateToSourceCode() }
                }
            }
        }
    }
    } // Scaffold

    if (showNtustLoginSheet) {
        LoginSheet(
            title = stringResource(R.string.settings_account_ntust_system),
            usernamePlaceholder = stringResource(R.string.login_student_id),
            passwordPlaceholder = stringResource(R.string.login_password),
            uppercaseInput = true,
            isLoggingIn = isNtustLoggingIn,
            loginError = ntustLoginError,
            onLogin = { u, p -> viewModel.loginNtust(u, p) },
            onDismiss = { showNtustLoginSheet = false },
        )
    }

    if (showLibraryLoginSheet) {
        LoginSheet(
            title = stringResource(R.string.settings_account_library_system),
            subtitle = stringResource(R.string.settings_library_account_subtitle),
            usernamePlaceholder = stringResource(R.string.library_login_username),
            passwordPlaceholder = stringResource(R.string.library_login_password),
            initialUsername = viewModel.ntustStudentId.orEmpty(),
            isLoggingIn = libIsLoggingIn,
            loginError = libLoginError,
            onLogin = { u, p -> viewModel.loginLibrary(u, p) },
            onDismiss = { showLibraryLoginSheet = false },
        )
    }

    if (showLibraryWarning) {
        LibraryWarningDialog(
            onConfirm = {
                viewModel.appState.libraryFeatureEnabled = true
                if (!viewModel.appState.configuredTabs.contains(AppFeature.LIBRARY) &&
                    viewModel.appState.configuredTabs.size < 4
                ) {
                    viewModel.appState.configuredTabs =
                        viewModel.appState.configuredTabs + AppFeature.LIBRARY
                }
                showLibraryWarning = false
            },
            onDismiss = { showLibraryWarning = false }
        )
    }

    if (viewModel.appState.pendingLibraryEnablePrompt) {
        AlertDialog(
            onDismissRequest = { viewModel.appState.pendingLibraryEnablePrompt = false },
            title = { Text(stringResource(R.string.settings_library_feature_disabled_title)) },
            text = { Text(stringResource(R.string.settings_library_feature_disabled_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.appState.pendingLibraryEnablePrompt = false }) {
                    Text(stringResource(R.string.settings_acknowledged))
                }
            },
        )
    }

    if (showResetColorsConfirm) {
        AlertDialog(
            onDismissRequest = { showResetColorsConfirm = false },
            title = { Text(stringResource(R.string.settings_reset_course_colors_confirm_title)) },
            text = { Text(stringResource(R.string.settings_reset_course_colors_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetCourseColors()
                    showResetColorsConfirm = false
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetColorsConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

private val SettingRowHeight = 56.dp

@Composable
private fun AccountRow(
    title: String,
    isLoggedIn: Boolean,
    subtitle: String?,
    extraSubtitle: String? = null,
    isLoggingIn: Boolean,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (isLoggedIn) Color(0xFF34C759) else Color(0xFFFF3B30))
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                )
            }
            if (!extraSubtitle.isNullOrBlank()) {
                Text(
                    extraSubtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                )
            }
        }
        if (isLoggingIn) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        } else if (isLoggedIn) {
            OutlinedButton(
                onClick = onLogout,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text(stringResource(R.string.action_logout)) }
        } else {
            Button(onClick = onLogin) { Text(stringResource(R.string.action_login)) }
        }
    }
}

@Composable
private fun SettingsRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SettingRowHeight)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY))
    }
}

@Composable
private fun SettingsToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SettingRowHeight)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        // iOS-style switch colors: the default M3 unchecked thumb is `outline`
        // which collapses to near-invisible dark gray on the dark track in
        // dark mode. Force a white thumb and a neutral track instead.
        val isDark = TigerDuckTheme.isDarkMode
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = if (isDark) Color(0xFF39393D) else Color(0xFFE9E9EB),
                uncheckedBorderColor = Color.Transparent,
            )
        )
    }
}

@Composable
private fun SettingsPickerRow(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    selectedKey: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SettingRowHeight)
            .clickable { expanded = true }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box {
            Text(value, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY))

            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, shape = RoundedCornerShape(12.dp)) {
                options.forEach { (key, display) ->
                    DropdownMenuItem(
                        text = { Text(display) },
                        onClick = {
                            onSelect(key)
                            expanded = false
                        },
                        leadingIcon = {
                            RadioButton(
                                selected = selectedKey == key,
                                onClick = null
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsLinkRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SettingRowHeight)
            .clickable { onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.DISABLED),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun SettingsLinkRowWithValue(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SettingRowHeight)
            .clickable { onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.DISABLED),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun LibraryWarningDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var countdown by remember { mutableIntStateOf(5) }
    var confirmEnabled by remember { mutableStateOf(false) }
    val view = LocalView.current

    LaunchedEffect(Unit) {
        // 1-second max vibration on dialog open
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vm = view.context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE)
                    as? android.os.VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            view.context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        }
        vibrator?.vibrate(
            android.os.VibrationEffect.createOneShot(1000, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
        )

        for (i in 4 downTo 0) {
            delay(1000)
            countdown = i
        }
        confirmEnabled = true
    }

    val infiniteTransition = rememberInfiniteTransition(label = "flash")
    val flashAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "flash_alpha"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = Color.Red.copy(alpha = flashAlpha),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.settings_library_warning_title),
                    color = Color.Red.copy(alpha = flashAlpha),
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Text(stringResource(R.string.settings_library_warning_message))
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = confirmEnabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Red,
                    disabledContainerColor = Color.Red.copy(alpha = 0.35f)
                )
            ) {
                Text(
                    if (confirmEnabled) stringResource(R.string.settings_library_warning_confirm)
                    else stringResource(R.string.settings_library_warning_confirm_countdown, countdown)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_library_warning_dismiss))
            }
        }
    )
}

private fun openUrl(context: android.content.Context, url: String, browserPreference: String) {
    val uri = url.toUri()
    if (browserPreference == "inApp") {
        CustomTabsIntent.Builder().build().launchUrl(context, uri)
    } else {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
}
