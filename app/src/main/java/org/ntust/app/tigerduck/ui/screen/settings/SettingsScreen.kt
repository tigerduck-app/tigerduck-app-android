// The Settings screen itself, in iOS's sections (tigerduck-app#939): Account, Appearance, the
// app's pages, TigerSync, notification permissions, Language, Other settings and About, with a
// search pulled down from under its title. Its rows are the catalog's (SettingsCatalog.kt), each
// page's on a page of its own (SettingsPages.kt); the row primitives live in SettingsRows.kt.

package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.EntryPoint
import kotlinx.coroutines.flow.first
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.ManualCheckResult
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.liveactivity.LiveUpdateSegments
import org.ntust.app.tigerduck.sensor.FlipDetector
import org.ntust.app.tigerduck.ui.component.PageHeaderPosition
import org.ntust.app.tigerduck.ui.component.PageScaffold
import org.ntust.app.tigerduck.ui.component.PageSettings
import org.ntust.app.tigerduck.ui.component.SearchDrawer
import org.ntust.app.tigerduck.ui.component.TigerDuckDialog
import org.ntust.app.tigerduck.ui.component.rememberSearchRevealState
import org.ntust.app.tigerduck.ui.screen.coursemoodle.rememberMoodleAppInstalled
import org.ntust.app.tigerduck.ui.screen.mail.MailAccountViewModel
import org.ntust.app.tigerduck.ui.screen.whatsnew.WhatsNewCatalog
import org.ntust.app.tigerduck.ui.screen.whatsnew.WhatsNewFlow
import org.ntust.app.tigerduck.ui.screen.whatsnew.WhatsNewSheet
import org.ntust.app.tigerduck.update.UpdateChecker
import org.ntust.app.tigerduck.update.WhatsNewGate
import org.ntust.app.tigerduck.update.WhatsNewRepository
import org.ntust.app.tigerduck.util.replaceIosArg
import org.ntust.app.tigerduck.wear.WearCompanion

/** The root's parts, in order: the catalog's sections, the pages after Appearance, and the debug build's own. */
internal enum class RootPart {
    ACCOUNT, APPEARANCE, PAGES, TIGERSYNC, NOTIFICATION_PERMISSIONS, LANGUAGE, OTHER, ABOUT, DEVELOPER;

    companion object {
        fun of(section: SettingsSection): RootPart = valueOf(section.name)

        /** The list's items before the first part: the title and the search drawer. */
        const val FIRST_INDEX = SearchPull.DRAWER_INDEX + 1
    }
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    open: (SettingsLink) -> Unit = {},
    openPage: (PageSettings) -> Unit = {},
) {
    val context = LocalContext.current
    val mailViewModel: MailAccountViewModel = hiltViewModel()
    val sheets = remember { AccountSheets() }
    val snackbarHostState = remember { SnackbarHostState() }
    val appVersion = remember { appVersionLabel(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE) }
    val browserPreference = viewModel.appState.browserPreference

    // About section dependencies: UpdateChecker for "Check for updates" and
    // WhatsNewRepository for the manual What's New entry point. Pulled via
    // a Hilt entry point so this purely-Compose screen doesn't have to thread
    // them through the existing SettingsViewModel just to render two rows.
    val settingsEntryPoint = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, SettingsEntryPoint::class.java)
    }
    val updateChecker = remember(settingsEntryPoint) { settingsEntryPoint.updateChecker() }
    val isCheckingForUpdate by updateChecker.isCheckingForUpdate.collectAsStateWithLifecycle()
    val whatsNew = rememberManualWhatsNew(viewModel, settingsEntryPoint.whatsNewRepository())
    val state = settingsState(hasWhatsNew = whatsNew.latest != null)
    NtustLoginErrorSnackbar(viewModel, snackbarHostState)

    PageScaffold(
        title = stringResource(R.string.feature_settings),
        headerPosition = PageHeaderPosition.InContent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { scaffoldPadding ->
        SettingsList(Modifier.padding(scaffoldPadding), state, openPage, header = { Header() }) { part ->
            when (part) {
                RootPart.ACCOUNT -> AccountSection(viewModel, mailViewModel, sheets)
                RootPart.APPEARANCE -> AppearanceSection(viewModel.appState, open)
                RootPart.PAGES -> PagesSection(state, openPage)
                RootPart.TIGERSYNC -> TigerSyncSection(state, open)
                RootPart.NOTIFICATION_PERMISSIONS -> NotificationPermissionsSection(open)
                RootPart.LANGUAGE -> LanguageSection(viewModel.appState.appLanguage, open)
                RootPart.OTHER -> OtherSection(viewModel, open)
                RootPart.ABOUT -> AboutSection(
                    state, appVersion, isCheckingForUpdate, updateChecker::checkManually, whatsNew::show, browserPreference, open,
                )
                RootPart.DEVELOPER -> DeveloperSection(viewModel, open)
            }
        }
    }

    AccountSheetsHost(viewModel, mailViewModel, sheets, browserPreference)
    ManualUpdateCheckResult(updateChecker)
    whatsNew.Sheet()
}

/**
 * The root's list: its [header], the search drawer, then each part, or while there is a query,
 * what it finds. A row found on the root goes back to the parts, scrolled to its section, as
 * iOS's search reveals it.
 */
@Composable
internal fun SettingsList(
    modifier: Modifier,
    state: SettingsState,
    openPage: (PageSettings) -> Unit,
    header: @Composable () -> Unit,
    initialQuery: String = "",
    part: @Composable (RootPart) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var searchFocused by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf<Reveal?>(null) }
    val listState = rememberLazyListState()
    val searchReveal = rememberSearchRevealState()
    searchReveal.pinned = searchFocused || query.isNotEmpty()
    val searchPull = remember(searchReveal, listState) { SearchPull(searchReveal, listState) }
    val focusManager = LocalFocusManager.current
    val parts = remember { RootPart.entries.filter { it != RootPart.DEVELOPER || BuildConfig.DEBUG } }
    LaunchedEffect(revealed) {
        val section = (revealed?.row?.place as? SettingsPlace.Section)?.section ?: return@LaunchedEffect
        val index = RootPart.FIRST_INDEX + parts.indexOf(RootPart.of(section))
        // The parts are back in the list from the layout after the query cleared, not this one.
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > index }
        listState.animateScrollToItem(index)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().nestedScroll(searchPull),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item(key = "header") { header() }
        item(key = "search") {
            SearchDrawer(searchReveal) { SettingsSearchField(query, { query = it }, { searchFocused = it }) }
        }
        if (query.isBlank()) {
            items(parts, key = { it }) { part(it) }
        } else {
            item(key = "results") {
                SettingsSearchResults(query, state, openPage) { row ->
                    focusManager.clearFocus()
                    query = ""
                    revealed = Reveal(row)
                }
            }
        }
    }
}

/**
 * A root row a search result asked to go back to. One for each tap, compared by identity, so the
 * same row found again scrolls again, even after a finger stopped the last scroll part of the way.
 */
private class Reveal(val row: SettingsRow)

/**
 * Shows a network error from the school sign-in as a snackbar; cleared after display so navigating
 * away and back doesn't re-surface a stale error.
 */
@Composable
private fun NtustLoginErrorSnackbar(viewModel: SettingsViewModel, snackbarHostState: SnackbarHostState) {
    val ntustLoginError by viewModel.ntustLoginError.collectAsStateWithLifecycle()
    LaunchedEffect(ntustLoginError) {
        val error = ntustLoginError ?: return@LaunchedEffect
        if (error.contains("連線") || error.contains("網路") ||
            error.contains("network", ignoreCase = true) || error.contains("connection", ignoreCase = true)
        ) {
            snackbarHostState.showSnackbar(error)
            viewModel.clearNtustLoginError()
        }
    }
}

/**
 * What Settings' rows are shown and gated by, followed live: who is signed in
 * ([SettingsGatesViewModel]), TigerSync, and what this phone and build have. [hasWhatsNew] is the
 * root's to know; no page has its row.
 */
@Composable
internal fun settingsState(hasWhatsNew: Boolean = false, gates: SettingsGatesViewModel = hiltViewModel()): SettingsState {
    val accounts by gates.accounts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val hasFlipToLibrary = remember(context) { FlipDetector.isSupported(context) }
    val hasLiveUpdateGradient = remember { LiveUpdateSegments.canShowGradient() }
    val hasMoodleApp = rememberMoodleAppInstalled()
    val appState = gates.appState
    return SettingsState(
        schoolSignedIn = accounts.school,
        mailSignedIn = accounts.mail,
        librarySignedIn = accounts.library,
        // Off at its source on fdroid (AppPreferences.kt), and so here.
        tigerSyncOn = appState.cloudSyncEnabled,
        coursesInEnglish = AppLanguageManager.isCourseApiEnglish(appState.appLanguage),
        usesEnglishClassroomNames = appState.useEnglishClassroomAbbreviation,
        hasFlipToLibrary = hasFlipToLibrary,
        hasWatch = WearCompanion.SHIPS,
        hasLiveUpdateGradient = hasLiveUpdateGradient,
        // fdroid leaves update notifications to the F-Droid client app.
        hasUpdateCheck = !BuildConfig.FLAVOR.equals("fdroid", ignoreCase = true),
        hasWhatsNew = hasWhatsNew,
        hasMoodleApp = hasMoodleApp,
    )
}

/**
 * Manual "Check for updates" result alert. Only mounts for UpToDate /
 * Failed — when Play reports an available update the regular
 * UpdatePromptHost surfaces the three-button dialog instead, and the
 * ManualCheckResult stays null in that branch by design.
 */
@Composable
private fun ManualUpdateCheckResult(updateChecker: UpdateChecker) {
    val result by updateChecker.lastManualCheckResult.collectAsStateWithLifecycle()
    val shown = result ?: return
    val appName = stringResource(R.string.app_name)
    TigerDuckDialog(
        onDismissRequest = { updateChecker.acknowledgeManualCheckResult() },
        title = stringResource(
            when (shown) {
                ManualCheckResult.UpToDate -> R.string.update_up_to_date_title
                ManualCheckResult.Failed -> R.string.update_check_failed_title
            },
        ),
        // update_up_to_date_message carries iOS's `%1$@` placeholder;
        // see IosPlaceholder.kt for the centralized shim.
        message = when (shown) {
            ManualCheckResult.UpToDate -> stringResource(R.string.update_up_to_date_message).replaceIosArg(1, appName)
            ManualCheckResult.Failed -> stringResource(R.string.update_check_failed_message)
        },
        confirmText = stringResource(R.string.action_got_it),
        onConfirm = { updateChecker.acknowledgeManualCheckResult() },
    )
}

/** About's What's new: the pages it would show now, and the sheet once its row is tapped. */
private class ManualWhatsNew(
    val latest: WhatsNewFlow?,
    private val now: () -> WhatsNewFlow?,
    private val shown: () -> WhatsNewFlow?,
    private val setShown: (ArrayList<String>?) -> Unit,
) {
    fun show() {
        setShown(ArrayList((now() ?: latest ?: return).pageIds))
    }

    /**
     * Always opens the latest registered version, even if the auto-launch path already showed it.
     * Does NOT stamp lastSeenWhatsNewVersionCode: this is a re-visit surface, and stamping here
     * would silently suppress the next auto-prompt after the user browsed release notes from
     * Settings.
     */
    @Composable
    fun Sheet() {
        shown()?.let { flow -> WhatsNewSheet(flow = flow, onDismiss = { setShown(null) }) }
    }
}

@Composable
private fun rememberManualWhatsNew(viewModel: SettingsViewModel, whatsNewRepo: WhatsNewRepository): ManualWhatsNew {
    // Resolve the user's locale once per Settings render — the asset isn't
    // huge, but re-parsing it for every recomposition would be wasteful. Key
    // on the resolved tag string (not the live Configuration object) so
    // unrelated config changes — font scale, screen size, dark-mode flip —
    // don't pointlessly re-parse the asset.
    val languageTag = LocalConfiguration.current.locales[0].toLanguageTag()
    val catalog = remember(viewModel.appState) { WhatsNewCatalog.pages(viewModel.appState) }
    val summaries = remember(whatsNewRepo, languageTag) { whatsNewRepo.summaries(languageTag) }
    val replay = remember(catalog, summaries) {
        WhatsNewGate.replay(catalog.keys, summaries.keys, upTo = BuildConfig.VERSION_CODE)
    }
    // The newest registered version's pages and summary, up to the installed
    // one, minus pages whose "only if this applies" check fails — the same
    // pages an upgrade would show. Keyed on the bottom bar, which those checks
    // read, so the row follows a change made since (in the tab editor, or by
    // answering a page of this very replay). The checks read more than the bar
    // (the library sign-in, say), so a tap on the row filters the pages again.
    val configuredTabs = viewModel.appState.configuredTabs
    val latest = remember(replay, configuredTabs) { replay?.let { WhatsNewFlow.from(it, catalog, summaries) } }
    // The open sheet's page ids: fixed when the row is tapped and saved across
    // a rotation, so the sheet keeps the same pages even after an answer in
    // it flips a page's check — re-filtering would shift every page after it
    // under the user's thumb. Null while the sheet is closed.
    var pageIds by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    val shown = remember(replay, pageIds) {
        pageIds?.let { ids -> replay?.let { WhatsNewFlow.from(it, catalog, summaries, restoredPageIds = ids) } }
    }
    return ManualWhatsNew(
        latest = latest,
        now = { replay?.let { WhatsNewFlow.from(it, catalog, summaries) } },
        shown = { shown },
        setShown = { pageIds = it },
    )
}

/**
 * The About section's version row: the version and, in brackets, the build
 * number, which tells test builds of one version apart in a bug report. The
 * iPhone shows "2.4.0 (Build 123)"; the bare number needs no translation.
 */
internal fun appVersionLabel(versionName: String, versionCode: Int): String = "$versionName ($versionCode)"

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface SettingsEntryPoint {
    fun updateChecker(): UpdateChecker
    fun whatsNewRepository(): WhatsNewRepository
}
