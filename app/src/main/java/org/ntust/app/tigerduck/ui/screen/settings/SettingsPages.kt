// The pages of Settings' Pages section, one per page of the app (tigerduck-app#939): each lists
// its rows from the catalog, as iOS's PageSettings.view does.

package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.BrowserPreference
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.PageScaffold
import org.ntust.app.tigerduck.ui.component.PageSettings
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.component.TigerDuckDialog
import org.ntust.app.tigerduck.ui.screen.announcements.SubscriptionSettingsScreen
import org.ntust.app.tigerduck.ui.screen.mail.SchoolMailSettingsScreen
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

/** A page Settings opens, which the app's navigation maps to its route. A [debugOnly] one is in a debug build only. */
enum class SettingsLink(val debugOnly: Boolean = false) {
    TAB_EDITOR, ACCENT_COLOR, DISPLAY_SIZE, CLOUD_SYNC, NOTIFICATION_PERMISSIONS, LANGUAGE, APP_ICON, VIBRATION,
    API_ENDPOINT, STORAGE, ABOUT_OTHERS, ASSIGNMENT_REMINDERS, LIVE_ACTIVITY, COURSE_NAME_SIZE, CLASS_REMINDERS,
    MAIL_NOTIFICATIONS, MAIL_GUIDE,
    DEBUG_TIME(true), DEBUG_NOTIFICATION(true), DEBUG_TRIGGERS(true), DEBUG_SERVER_FAILURE(true),
    DEBUG_OUTSIDE_APPS(true), DEBUG_MAIL_SERVER(true), DEBUG_BROWSER_ALLOWLIST(true), DEBUG_VIEWER_SAMPLES(true),
}

/**
 * [page]'s settings, opened from Settings' Pages section, a search result, or the page's own ⋮.
 * It closes once it has no row left to show, as after signing out of the library on its own page
 * of a phone that cannot sense the flip.
 */
@Composable
fun PageSettingsScreen(page: PageSettings, onBack: () -> Unit, open: (SettingsLink) -> Unit) {
    val state = settingsState()
    ClosesWhenEmpty(page, state, onBack)
    when (page) {
        PageSettings.BULLETINS -> BulletinsPageSettings(state, onBack)
        PageSettings.MAIL -> SchoolMailSettingsScreen(state, onBack, open)
        PageSettings.LIBRARY -> LibrarySettingsScreen(state, onBack)
        PageSettings.HOME, PageSettings.CLASS_TABLE, PageSettings.BROWSER, PageSettings.MOODLE ->
            PageScaffold(title = stringResource(page.title), onBack = onBack) { padding ->
                val viewModel: SettingsViewModel = hiltViewModel()
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
                ) {
                    item {
                        Column {
                            when (page) {
                                PageSettings.HOME -> HomePageSettings(viewModel.appState, state, open)
                                PageSettings.CLASS_TABLE -> ClassTablePageSettings(viewModel, state, open)
                                PageSettings.BROWSER -> BrowserPageSettings(viewModel.appState, open)
                                else -> MoodlePageSettings(viewModel, state, open)
                            }
                        }
                    }
                }
            }
    }
}

/**
 * Closes [page] once [state] leaves it no row to show, as iOS's page leaves, and only once it is
 * on top: composed as a predictive back gesture's preview, under the page still current, it is
 * not, and closing then would pop that page instead.
 */
@Composable
internal fun ClosesWhenEmpty(page: PageSettings, state: SettingsState, onBack: () -> Unit) {
    if (page.isShown(state)) return
    val close by rememberUpdatedState(onBack)
    LifecycleResumeEffect(Unit) {
        close()
        onPauseOrDispose { }
    }
}

/** A row that opens a page of its own, dimmed while its gate is closed. */
@Composable
internal fun PageLinkRow(row: SettingsRow, state: SettingsState, onClick: () -> Unit) {
    SettingsLinkRow(row.label(), closedReason = row.closedText(state), onClick = onClick)
}

@Composable
internal fun PageGap() = Spacer(Modifier.height(24.dp))

@Composable
internal fun PageFootnote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
}

/**
 * Home's: how a due date reads and which way the time slider runs, then its reminders and Live
 * Updates, which the phone runs itself, TigerSync on or off.
 */
@Composable
private fun HomePageSettings(appState: AppState, state: SettingsState, open: (SettingsLink) -> Unit) {
    ContentCard {
        Column {
            SettingsToggleRow(SettingsRow.SHOW_FULL_DUE_DATE.label(), appState.showAbsoluteAssignmentTime) {
                appState.showAbsoluteAssignmentTime = it
            }
            HorizontalDivider()
            SettingsToggleRow(SettingsRow.INVERT_SLIDER.label(), appState.invertSliderDirection) {
                appState.invertSliderDirection = it
            }
        }
    }
    PageGap()
    ContentCard {
        Column {
            PageLinkRow(SettingsRow.ASSIGNMENT_REMINDERS, state) { open(SettingsLink.ASSIGNMENT_REMINDERS) }
            HorizontalDivider()
            PageLinkRow(SettingsRow.LIVE_ACTIVITY, state) { open(SettingsLink.LIVE_ACTIVITY) }
        }
    }
}

/**
 * The class table's: today's courses above the grid and what each cell shows, the course name's
 * size, the class reminders, the English abbreviations where course names come in English, and
 * new colors for every course.
 */
@Composable
private fun ClassTablePageSettings(viewModel: SettingsViewModel, state: SettingsState, open: (SettingsLink) -> Unit) {
    val appState = viewModel.appState
    ContentCard {
        Column {
            SettingsToggleRow(SettingsRow.PIN_TODAY_COURSES.label(), appState.pinTodayCoursesOnClassTable) {
                appState.pinTodayCoursesOnClassTable = it
            }
            HorizontalDivider()
            SettingsToggleRow(SettingsRow.SHOW_CLASSROOM.label(), appState.showClassroomInClassTable) {
                appState.showClassroomInClassTable = it
            }
            HorizontalDivider()
            SettingsToggleRow(SettingsRow.OUTLINE_REQUIRED_COURSES.label(), appState.outlineRequiredCourses) {
                appState.outlineRequiredCourses = it
            }
            HorizontalDivider()
            SettingsToggleRow(SettingsRow.SHOW_ALL_PERIODS.label(), appState.alwaysShowAllPeriods) {
                appState.alwaysShowAllPeriods = it
            }
        }
    }
    PageGap()
    ContentCard { PageLinkRow(SettingsRow.COURSE_NAME_SIZE, state) { open(SettingsLink.COURSE_NAME_SIZE) } }
    PageFootnote(stringResource(R.string.settings_font_size_summary))
    PageGap()
    ContentCard { PageLinkRow(SettingsRow.CLASS_REMINDERS, state) { open(SettingsLink.CLASS_REMINDERS) } }
    if (SettingsRow.ENGLISH_COURSE_NAMES.isShown(state)) {
        PageGap()
        SectionHeader(stringResource(R.string.settings_section_abbreviation))
        ContentCard { AbbreviationRows(appState, state) }
    }
    PageGap()
    ReassignCourseColors(viewModel::resetCourseColors)
}

@Composable
private fun AbbreviationRows(appState: AppState, state: SettingsState) {
    Column {
        SettingsToggleRow(SettingsRow.ENGLISH_COURSE_NAMES.label(), appState.useEnglishCourseAbbreviation) {
            appState.useEnglishCourseAbbreviation = it
        }
        HorizontalDivider()
        SettingsToggleRow(SettingsRow.ENGLISH_CLASSROOM_NAMES.label(), appState.useEnglishClassroomAbbreviation) {
            appState.useEnglishClassroomAbbreviation = it
        }
        if (SettingsRow.MANDARIN_CLASSROOM_NAMES.isShown(state)) {
            HorizontalDivider()
            val options = listOf(
                AppPreferences.CLASSROOM_MANDARIN_DISPLAY_ORIGINAL to stringResource(R.string.settings_classroom_mandarin_display_original),
                AppPreferences.CLASSROOM_MANDARIN_DISPLAY_PINYIN to stringResource(R.string.settings_classroom_mandarin_display_pinyin),
                AppPreferences.CLASSROOM_MANDARIN_DISPLAY_TRANSLATED to stringResource(R.string.settings_classroom_mandarin_display_translated),
            )
            val selected = appState.classroomMandarinDisplay
            SettingsPickerRow(
                label = SettingsRow.MANDARIN_CLASSROOM_NAMES.label(),
                // Anything else stored reads as the original, as the class table shows it.
                value = options.firstOrNull { it.first == selected }?.second ?: options.first().second,
                options = options,
                selectedKey = selected,
                onSelect = { appState.classroomMandarinDisplay = it },
            )
        }
    }
}

/** New colors for every course, once the user says so. */
@Composable
private fun ReassignCourseColors(reset: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    ContentCard { SettingsActionRow(SettingsRow.REASSIGN_COURSE_COLORS.label()) { confirming = true } }
    if (confirming) {
        TigerDuckDialog(
            onDismissRequest = { confirming = false },
            title = stringResource(R.string.settings_reset_course_colors_confirm_title),
            message = stringResource(R.string.settings_reset_course_colors_confirm_message),
            confirmText = stringResource(R.string.action_confirm),
            onConfirm = {
                reset()
                confirming = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { confirming = false },
        )
    }
}

/** The browser's: where links open, and its storage, which the Storage page clears with the rest. */
@Composable
private fun BrowserPageSettings(appState: AppState, open: (SettingsLink) -> Unit) {
    val system = stringResource(R.string.settings_browser_system_default)
    val inApp = stringResource(R.string.settings_browser_in_app)
    ContentCard {
        SettingsPickerRow(
            label = SettingsRow.LINK_OPENING_METHOD.label(),
            value = if (appState.browserPreference == BrowserPreference.IN_APP) inApp else system,
            options = listOf(BrowserPreference.SYSTEM to system, BrowserPreference.IN_APP to inApp),
            selectedKey = appState.browserPreference,
            onSelect = { appState.browserPreference = it },
        )
    }
    PageGap()
    ContentCard { SettingsLinkRow(SettingsRow.WEBSITE_DATA.label()) { open(SettingsLink.STORAGE) } }
}

/** Moodle's: where "Open in Moodle" goes while the Moodle app is there, Pin myself, and its storage. */
@Composable
private fun MoodlePageSettings(viewModel: SettingsViewModel, state: SettingsState, open: (SettingsLink) -> Unit) {
    val appState = viewModel.appState
    ContentCard {
        Column {
            if (SettingsRow.MOODLE_OPEN_IN.isShown(state)) {
                MoodleOpenInSetting(installed = true, appState.moodleOpenIn) { appState.moodleOpenIn = it }
                HorizontalDivider()
            }
            PinMyselfSetting(viewModel.prefs.pinMyselfInCourseMembers)
        }
    }
    PageGap()
    ContentCard { SettingsLinkRow(SettingsRow.MOODLE_STORAGE.label()) { open(SettingsLink.STORAGE) } }
}

/**
 * Bulletins': signed in, the push settings themselves, as on iOS. Signed out, the backend keeps no
 * rules (backend #89), so their row stays, dimmed, and says to sign in.
 */
@Composable
private fun BulletinsPageSettings(state: SettingsState, onBack: () -> Unit) {
    if (state.schoolSignedIn) {
        SubscriptionSettingsScreen(signedIn = true, onBack = onBack)
        return
    }
    PageScaffold(title = stringResource(PageSettings.BULLETINS.title), onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        ) {
            item { ContentCard { PageLinkRow(SettingsRow.BULLETIN_PUSH, state) {} } }
        }
    }
}
