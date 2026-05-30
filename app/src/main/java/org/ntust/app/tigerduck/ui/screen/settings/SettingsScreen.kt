package org.ntust.app.tigerduck.ui.screen.settings

import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.sensor.FlipDetector
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.PageHeader
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.component.TigerDuckDialog
import org.ntust.app.tigerduck.ui.haptics.HapticScenario
import org.ntust.app.tigerduck.ui.haptics.Haptics
import org.ntust.app.tigerduck.ui.screen.whatsnew.WhatsNewDialog
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import org.ntust.app.tigerduck.ui.theme.tigerDuckSwitchColors
import org.ntust.app.tigerduck.update.ManualCheckResult
import org.ntust.app.tigerduck.update.UpdateChecker
import org.ntust.app.tigerduck.update.WhatsNewContent
import org.ntust.app.tigerduck.update.WhatsNewRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateToTabEditor: () -> Unit = {},
    onNavigateToLanguagePicker: () -> Unit = {},
    onNavigateToLiveActivity: () -> Unit = {},
    onNavigateToServerPush: () -> Unit = {},
    onNavigateToOtherSettings: () -> Unit = {},
    onNavigateToDebug: () -> Unit = {},
    onNavigateToNotificationDebug: () -> Unit = {},
    onNavigateToApiEndpointDebug: () -> Unit = {},
    onNavigateToTriggersDebug: () -> Unit = {},
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
    var showLibraryWarning by remember { mutableStateOf(false) }

    // Auto-dismiss dialogs when login succeeds
    LaunchedEffect(isNtustLoggedIn) {
        if (isNtustLoggedIn) showNtustLoginSheet = false
    }
    LaunchedEffect(isLibraryLoggedIn) {
        if (isLibraryLoggedIn) showLibraryLoginSheet = false
    }

    val accentColorHex = viewModel.appState.accentColorHex
    val showAbsoluteTime = viewModel.appState.showAbsoluteAssignmentTime
    val rememberAnnouncementFilter = viewModel.appState.rememberAnnouncementFilter
    val browserPreference = viewModel.appState.browserPreference
    val useEnglishCourseAbbreviation = viewModel.appState.useEnglishCourseAbbreviation
    val useEnglishClassroomAbbreviation = viewModel.appState.useEnglishClassroomAbbreviation
    val classroomMandarinDisplay = viewModel.appState.classroomMandarinDisplay
    val notifyAssignments = viewModel.appState.notifyAssignments
    val libraryEnabled = viewModel.appState.libraryFeatureEnabled
    val appLanguage = viewModel.appState.appLanguage
    val shouldShowEnglishAbbreviationToggle = AppLanguageManager.isCourseApiEnglish(appLanguage)

    val snackbarHostState = remember { SnackbarHostState() }

    val appVersion = remember { BuildConfig.VERSION_NAME }

    // About section dependencies: UpdateChecker for "Check for updates" and
    // WhatsNewRepository for the manual What's New entry point. Pulled via
    // a Hilt entry point so this purely-Compose screen doesn't have to thread
    // them through the existing SettingsViewModel just to render two rows.
    val settingsEntryPoint = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            SettingsEntryPoint::class.java,
        )
    }
    val updateChecker = remember(settingsEntryPoint) { settingsEntryPoint.updateChecker() }
    val whatsNewRepo = remember(settingsEntryPoint) { settingsEntryPoint.whatsNewRepository() }
    val isCheckingForUpdate by updateChecker.isCheckingForUpdate.collectAsStateWithLifecycle()
    val manualCheckResult by updateChecker.lastManualCheckResult.collectAsStateWithLifecycle()
    // Resolve the user's locale once per Settings render — the asset isn't
    // huge, but re-parsing it for every recomposition would be wasteful.
    val languageTag = remember(context.resources.configuration) {
        context.resources.configuration.locales[0].toLanguageTag()
    }
    val latestWhatsNew: WhatsNewContent? = remember(whatsNewRepo, languageTag) {
        whatsNewRepo.latestEntry(languageTag)
    }
    var manualWhatsNewVisible by remember { mutableStateOf(false) }

    // Show network error as snackbar; clear after display so navigating
    // away and back doesn't re-surface a stale error.
    LaunchedEffect(ntustLoginError) {
        val error = ntustLoginError ?: return@LaunchedEffect
        if (
            error.contains("連線") ||
            error.contains("網路") ||
            error.contains("network", ignoreCase = true) ||
            error.contains("connection", ignoreCase = true)
        ) {
            snackbarHostState.showSnackbar(error)
            viewModel.clearNtustLoginError()
        }
    }

    // Consume the deep-link signal raised by the signed-out empty-state lock
    // icons. Capture it into local state so we can pulse exactly once and
    // re-arm on a future entry, even if the user backs out and taps again.
    var highlightNtustRow by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel.appState.pendingNtustSignInHighlight) {
        if (viewModel.appState.pendingNtustSignInHighlight) {
            highlightNtustRow = true
            viewModel.appState.pendingNtustSignInHighlight = false
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                PageHeader(title = stringResource(R.string.feature_settings))
            }

            // MARK: Account section
            item { SectionHeader(stringResource(R.string.settings_section_account)) }
            item {
                val accountButtonMinWidth = rememberAccountButtonMinWidth()
                ContentCard {
                    Column {
                        AccountRow(
                            title = stringResource(R.string.settings_account_ntust_system),
                            isLoggedIn = isNtustLoggedIn,
                            subtitle = if (isNtustLoggedIn) viewModel.ntustStudentId else null,
                            isLoggingIn = isNtustLoggingIn,
                            onLogin = { showNtustLoginSheet = true },
                            onLogout = { viewModel.logoutNtust() },
                            actionMinWidth = accountButtonMinWidth,
                            highlight = highlightNtustRow,
                            onHighlightConsumed = { highlightNtustRow = false },
                        )

                        if (libraryEnabled) {
                            HorizontalDivider()
                            val expiryMs = viewModel.libraryTokenExpiry
                            val expirySubtitle = if (isLibraryLoggedIn && expiryMs > 0) {
                                val fmt = SimpleDateFormat("yyyy/MM/dd", Locale.TAIWAN).apply {
                                    timeZone = org.ntust.app.tigerduck.AppConstants.TAIPEI_TZ
                                }
                                stringResource(
                                    R.string.settings_token_valid_until,
                                    fmt.format(Date(expiryMs))
                                )
                            } else null
                            AccountRow(
                                title = stringResource(R.string.settings_account_library_system),
                                isLoggedIn = isLibraryLoggedIn,
                                subtitle = if (isLibraryLoggedIn) viewModel.libraryUsername else null,
                                extraSubtitle = expirySubtitle,
                                isLoggingIn = libIsLoggingIn,
                                onLogin = { showLibraryLoginSheet = true },
                                onLogout = { viewModel.logoutLibrary() },
                                actionMinWidth = accountButtonMinWidth,
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                stringResource(R.string.settings_accent_color),
                                style = MaterialTheme.typography.bodyMedium
                            )
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
                                            .clickable {
                                                viewModel.appState.accentColorHex = canonicalHex
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (accentColorHex == canonicalHex) {
                                            Text(
                                                "\u2713",
                                                color = Color.White,
                                                style = MaterialTheme.typography.labelSmall
                                            )
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
                        SettingsToggleRow(
                            stringResource(R.string.settings_show_absolute_assignment_time),
                            showAbsoluteTime
                        ) {
                            viewModel.appState.showAbsoluteAssignmentTime = it
                        }
                        HorizontalDivider()
                        SettingsToggleRow(
                            stringResource(R.string.settings_remember_bulletin_filter),
                            rememberAnnouncementFilter
                        ) {
                            viewModel.appState.rememberAnnouncementFilter = it
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
                    }
                }
            }

            // MARK: Abbreviations
            if (shouldShowEnglishAbbreviationToggle) {
                item { SectionHeader(stringResource(R.string.settings_section_abbreviation)) }
                item {
                    ContentCard {
                        Column {
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
                        SettingsToggleRow(
                            stringResource(R.string.settings_assignment_due_reminder),
                            notifyAssignments
                        ) {
                            viewModel.appState.notifyAssignments = it
                            if (!it) viewModel.cancelAllAssignmentNotifications()
                        }
                        HorizontalDivider()
                        SettingsLinkRow(stringResource(R.string.live_activity_channel_name)) { onNavigateToLiveActivity() }
                        // Hide on F-Droid flavor since the Server Push pipeline
                        // (FCM) isn't compiled in there — same rule as
                        // SubscriptionSettingsScreen's existing toggle gate.
                        if (!BuildConfig.FLAVOR.equals("fdroid", ignoreCase = true)) {
                            HorizontalDivider()
                            SettingsLinkRow(stringResource(R.string.settings_push_server_nav_label)) { onNavigateToServerPush() }
                        }
                    }
                }
            }

            // MARK: Other settings
            item { SectionHeader(stringResource(R.string.settings_section_other_settings)) }
            item {
                val flipSensorSupported = remember(context) {
                    FlipDetector.isSupported(context)
                }
                ContentCard {
                    Column {
                        SettingsToggleRow(
                            stringResource(R.string.settings_library_related_features),
                            libraryEnabled,
                        ) { enabled ->
                            if (enabled) {
                                showLibraryWarning = true
                            } else {
                                viewModel.appState.libraryFeatureEnabled = false
                                viewModel.appState.configuredTabs =
                                    viewModel.appState.configuredTabs.filter { !it.isLibraryRelated }
                            }
                        }
                        if (libraryEnabled) {
                            HorizontalDivider()
                            SettingsToggleRow(
                                label = stringResource(R.string.settings_flip_to_library_title),
                                checked = viewModel.appState.flipToLibraryEnabled && flipSensorSupported,
                                enabled = flipSensorSupported,
                                subtitle = if (flipSensorSupported) {
                                    stringResource(R.string.settings_flip_to_library_summary)
                                } else {
                                    stringResource(R.string.settings_flip_to_library_unsupported)
                                },
                                onCheckedChange = { viewModel.appState.flipToLibraryEnabled = it },
                            )
                        }
                        HorizontalDivider()
                        SettingsLinkRow(stringResource(R.string.settings_section_other_settings)) { onNavigateToOtherSettings() }
                    }
                }
            }

            // MARK: Language
            item { SectionHeader(stringResource(R.string.feature_category_language)) }
            item {
                ContentCard {
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
                }
            }

            // MARK: About
            item { SectionHeader(stringResource(R.string.settings_section_about)) }
            item {
                ContentCard {
                    Column {
                        SettingsRow(stringResource(R.string.settings_version), appVersion)
                        // Check for updates — Play flavor only. fdroid leaves
                        // update notifications to the F-Droid client app (the
                        // UpdateChecker stub is a no-op there), so a row that
                        // could only ever report "up to date" would just
                        // confuse users. Mirrors iOS hiding this row on Mac.
                        if (!BuildConfig.FLAVOR.equals("fdroid", ignoreCase = true)) {
                            HorizontalDivider()
                            CheckForUpdatesRow(
                                isChecking = isCheckingForUpdate,
                                onClick = { updateChecker.checkManually() },
                            )
                        }
                        // What's New — only when an entry is registered for
                        // the resolved locale. During early bring-up of a
                        // release the asset may not yet have an entry; in
                        // that case we hide the row instead of routing the
                        // user to an empty sheet.
                        if (latestWhatsNew != null) {
                            HorizontalDivider()
                            SettingsLinkRow(stringResource(R.string.settings_whats_new)) {
                                manualWhatsNewVisible = true
                            }
                        }
                        HorizontalDivider()
                        SettingsLinkRow(stringResource(R.string.settings_official_website)) {
                            openUrl(context, "https://tigerduck.app/", browserPreference)
                        }
                    }
                }
            }

            // MARK: Developer (debug builds only)
            if (BuildConfig.DEBUG) {
                item { SectionHeader("Developer") }
                item {
                    ContentCard {
                        Column {
                            SettingsLinkRow("Time override") { onNavigateToDebug() }
                            HorizontalDivider()
                            SettingsLinkRow("Notification") { onNavigateToNotificationDebug() }
                            HorizontalDivider()
                            SettingsLinkRow("API endpoint") { onNavigateToApiEndpointDebug() }
                            HorizontalDivider()
                            // One-shot UI surfaces (What's new, update prompt,
                            // flip-to-library first trigger) live behind here so
                            // they can be re-fired after a single dismissal.
                            SettingsLinkRow("Triggers") { onNavigateToTriggersDebug() }
                        }
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
            onDismiss = { showLibraryWarning = false },
        )
    }

    if (viewModel.appState.pendingLibraryEnablePrompt) {
        TigerDuckDialog(
            onDismissRequest = { viewModel.appState.pendingLibraryEnablePrompt = false },
            title = stringResource(R.string.settings_library_feature_disabled_title),
            message = stringResource(R.string.settings_library_feature_disabled_message),
            confirmText = stringResource(R.string.settings_acknowledged),
            onConfirm = { viewModel.appState.pendingLibraryEnablePrompt = false },
        )
    }

    // Manual "Check for updates" result alert. Only mounts for UpToDate /
    // Failed — when Play reports an available update the regular
    // UpdatePromptHost surfaces the three-button dialog instead, and the
    // ManualCheckResult stays null in that branch by design.
    manualCheckResult?.let { result ->
        val appName = stringResource(R.string.app_name)
        TigerDuckDialog(
            onDismissRequest = { updateChecker.acknowledgeManualCheckResult() },
            title = stringResource(
                when (result) {
                    ManualCheckResult.UpToDate -> R.string.update_up_to_date_title
                    ManualCheckResult.Failed -> R.string.update_check_failed_title
                },
            ),
            // update_up_to_date_message carries the iOS-style `%1$@`
            // placeholder; hand-replace so the existing localized string
            // works without a submodule bump just for this placeholder shape.
            message = when (result) {
                ManualCheckResult.UpToDate ->
                    stringResource(R.string.update_up_to_date_message).replace("%1\$@", appName)
                ManualCheckResult.Failed ->
                    stringResource(R.string.update_check_failed_message)
            },
            confirmText = stringResource(R.string.action_got_it),
            onConfirm = { updateChecker.acknowledgeManualCheckResult() },
        )
    }

    // Manual "What's New" — always opens the latest authored entry, even
    // if the auto-launch path already showed it. Does NOT stamp
    // lastSeenWhatsNewVersionCode: this is a re-visit surface, and stamping
    // here would silently suppress the next auto-prompt after the user
    // browsed release notes from Settings.
    if (manualWhatsNewVisible && latestWhatsNew != null) {
        WhatsNewDialog(
            content = latestWhatsNew,
            onDismiss = { manualWhatsNewVisible = false },
        )
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface SettingsEntryPoint {
    fun updateChecker(): UpdateChecker
    fun whatsNewRepository(): WhatsNewRepository
}

/**
 * About section row that swaps a chevron for a spinner while a manual
 * "Check for updates" call is in flight. Disabled while checking so a
 * stuck-finger user can't fire a second concurrent query.
 */
@Composable
private fun CheckForUpdatesRow(isChecking: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SettingRowHeight)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .clickable(enabled = !isChecking) { onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.settings_check_for_updates),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (isChecking) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.DISABLED),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

internal val SettingRowHeight = 56.dp

/**
 * Width that fits whichever of "Sign in" / "Sign out" is wider, so the
 * two buttons line up when both are visible (one row logged-in, one not).
 * Adds the M3 button horizontal content padding (24.dp each side).
 */
@Composable
private fun rememberAccountButtonMinWidth(): Dp {
    val loginText = stringResource(R.string.action_login)
    val logoutText = stringResource(R.string.action_logout)
    val style = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelWidthPx = remember(loginText, logoutText, style) {
        maxOf(
            measurer.measure(loginText, style).size.width,
            measurer.measure(logoutText, style).size.width,
        )
    }
    return with(density) { labelWidthPx.toDp() } + 48.dp
}

@Composable
private fun AccountRow(
    title: String,
    isLoggedIn: Boolean,
    subtitle: String?,
    extraSubtitle: String? = null,
    isLoggingIn: Boolean,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    actionMinWidth: Dp,
    highlight: Boolean = false,
    onHighlightConsumed: () -> Unit = {},
) {
    // Two-pulse attention flash when an off-screen surface (e.g. a
    // signed-out empty state) deep-links here to surface the "Sign in"
    // action. Uses keyframes so the row briefly tints with the accent
    // container, fades, tints again, then settles back — enough motion
    // to catch the eye without being noisy.
    val highlightAlpha = remember { Animatable(0f) }
    LaunchedEffect(highlight) {
        if (!highlight) return@LaunchedEffect
        highlightAlpha.snapTo(0f)
        highlightAlpha.animateTo(
            targetValue = 0f,
            animationSpec = keyframes {
                durationMillis = 2200
                0f at 0
                1f at 250
                0f at 900
                1f at 1200
                0f at 1900
            },
        )
        onHighlightConsumed()
    }
    val highlightColor = MaterialTheme.colorScheme.primaryContainer
        .copy(alpha = 0.55f * highlightAlpha.value)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(highlightColor)
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
            Box(
                modifier = Modifier
                    .widthIn(min = actionMinWidth)
                    .height(ButtonDefaults.MinHeight),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
        } else if (isLoggedIn) {
            OutlinedButton(
                onClick = onLogout,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                modifier = Modifier.widthIn(min = actionMinWidth),
            ) { Text(stringResource(R.string.action_logout)) }
        } else {
            Button(
                onClick = onLogin,
                modifier = Modifier.widthIn(min = actionMinWidth),
            ) { Text(stringResource(R.string.action_login)) }
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
        Text(
            value, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
        )
    }
}

@Composable
internal fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    subtitle: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SettingRowHeight)
            .padding(horizontal = 16.dp, vertical = if (subtitle != null) 8.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(
                    alpha = if (enabled) 1f else ContentAlpha.DISABLED,
                ),
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (enabled) ContentAlpha.SECONDARY else ContentAlpha.DISABLED,
                    ),
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = tigerDuckSwitchColors(),
        )
    }
}

@Composable
internal fun SettingsPickerRow(
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
            // `heightIn` (not `height`) so a long label that wraps to two
            // lines can grow the row instead of getting its descenders
            // clipped — e.g. Mandarin labels like "中文教室名稱顯示方式"
            // are tall enough to need the extra room.
            .heightIn(min = SettingRowHeight)
            .clickable { expanded = true }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box {
            Text(
                value, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
            )

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                shape = RoundedCornerShape(12.dp)
            ) {
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
internal fun SettingsLinkRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SettingRowHeight)
            .semantics(mergeDescendants = true) { role = Role.Button }
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
            .semantics(mergeDescendants = true) { role = Role.Button }
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
internal fun LibraryWarningDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var countdown by remember { mutableIntStateOf(5) }
    var confirmEnabled by remember { mutableStateOf(false) }
    val view = LocalView.current

    LaunchedEffect(Unit) {
        Haptics.perform(
            view.context,
            HapticScenario.LibraryWarning,
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

    TigerDuckDialog(
        onDismissRequest = onDismiss,
        icon = {
            // Flashing red warning icon + title to signal the destructive
            // weight of enabling the library feature. Kept in the icon slot
            // (rather than the plain `title`) so both can pulse together.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = Color.Red.copy(alpha = flashAlpha),
                    modifier = Modifier.size(32.dp)
                )
                Text(
                    stringResource(R.string.settings_library_warning_title),
                    color = Color.Red.copy(alpha = flashAlpha),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
        },
        message = stringResource(R.string.settings_library_warning_message),
        confirmText = if (confirmEnabled) stringResource(R.string.settings_library_warning_confirm)
        else stringResource(R.string.settings_library_warning_confirm_countdown, countdown),
        onConfirm = onConfirm,
        confirmEnabled = confirmEnabled,
        confirmColors = ButtonDefaults.buttonColors(
            containerColor = Color.Red,
            disabledContainerColor = Color.Red.copy(alpha = 0.35f),
        ),
        dismissText = stringResource(R.string.settings_library_warning_dismiss),
        onDismiss = onDismiss,
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
