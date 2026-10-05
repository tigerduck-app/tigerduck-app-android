package org.ntust.app.tigerduck

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.analytics.AnalyticsLogger
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.liveactivity.LiveActivityManager
import org.ntust.app.tigerduck.network.ApiVersionGate
import org.ntust.app.tigerduck.network.MoodleTokenService
import org.ntust.app.tigerduck.notification.BackgroundSyncWorker
import org.ntust.app.tigerduck.push.FcmBootstrap
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.serverpush.ServerPopupRequest
import org.ntust.app.tigerduck.serverpush.ServerPushIntentToken
import org.ntust.app.tigerduck.serverpush.ServerPushPopupCoordinator
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.component.TigerDuckDialog
import org.ntust.app.tigerduck.ui.firsttrigger.FirstTriggerPromptController
import org.ntust.app.tigerduck.ui.firsttrigger.FirstTriggerPromptHost
import org.ntust.app.tigerduck.ui.navigation.AppNavigation
import org.ntust.app.tigerduck.ui.screen.update.UpdatePromptDialog
import org.ntust.app.tigerduck.ui.screen.whatsnew.WhatsNewCatalog
import org.ntust.app.tigerduck.ui.screen.whatsnew.WhatsNewFlow
import org.ntust.app.tigerduck.ui.screen.whatsnew.WhatsNewSheet
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import org.ntust.app.tigerduck.update.UpdateChecker
import org.ntust.app.tigerduck.update.WhatsNewGate
import org.ntust.app.tigerduck.update.WhatsNewRepository

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var analyticsLogger: AnalyticsLogger

    @Inject
    lateinit var appState: AppState

    @Inject
    lateinit var liveActivityManager: LiveActivityManager

    @Inject
    lateinit var academicCalendar: org.ntust.app.tigerduck.academic.AcademicCalendarStore

    @Inject
    lateinit var authService: AuthService

    @Inject
    lateinit var updateChecker: UpdateChecker

    @Inject
    lateinit var whatsNewRepository: WhatsNewRepository

    @Inject
    lateinit var appPreferences: AppPreferences

    @Inject
    lateinit var serverPushPopupCoordinator: ServerPushPopupCoordinator

    @Inject
    lateinit var serverPushIntentToken: ServerPushIntentToken

    @Inject
    lateinit var firstTriggerPromptController: FirstTriggerPromptController

    @Inject
    lateinit var moodleTokenService: MoodleTokenService

    @Inject
    lateinit var pushApiClient: PushApiClient

    @Inject
    lateinit var authTokenManager: AuthTokenManager

    @Inject
    lateinit var fcmBootstrap: FcmBootstrap

    @Inject
    lateinit var mailChecker: org.ntust.app.tigerduck.mail.sync.MailChecker

    private val widgetStartRoute = mutableStateOf<String?>(null)
    private val whatsNewFlow = mutableStateOf<WhatsNewFlow?>(null)

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            appState.systemPermissions.recordLaunchPromptResult(granted)
            if (granted) liveActivityManager.refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        volumeControlStream = AudioManager.STREAM_NOTIFICATION

        applyRotationPreference()
        // Fresh start only. uiMode, fontScale and density are not in the
        // manifest's configChanges, so a dark-mode switch recreates this
        // Activity — and HyperOS raises the prompt again on every request.
        if (savedInstanceState == null) requestNotificationPermissionIfNeeded()
        // Only schedule on the first Activity creation. WorkManager.UPDATE would
        // be idempotent, but re-enqueuing on every rotation/config change is
        // wasted work (and thrashes WorkManager's internal bookkeeping DB).
        if (savedInstanceState == null && authService.storedStudentId != null) {
            BackgroundSyncWorker.schedule(applicationContext)
            lifecycleScope.launch { authService.migrateToV3IfNeeded() }
        }

        widgetStartRoute.value = resolveStartRoute(intent)
        handleServerPushIntent(intent)

        // Re-prompt for app updates only on a genuine fresh start, never on a
        // rotation/config-change recreation (issue #89).
        if (savedInstanceState == null) {
            updateChecker.maybePromptForUpdate()
        }
        // Resolve "What's new" on every onCreate, including config-change
        // recreations: the sheet's versionCode is recorded only once the user
        // dismisses it (see resolveWhatsNew), so re-deriving here re-shows a
        // sheet the user had not yet dismissed instead of dropping it
        // permanently on rotation (issue #89). A recreation also keeps the
        // debug "Replay" sentinel from being consumed and restores the exact
        // pages that were on screen.
        resolveWhatsNew(savedInstanceState)

        setContent {
            // Re-apply orientation whenever the user changes the setting
            // from within the app — Settings lives inside this Activity, so
            // onResume never fires on return.
            LaunchedEffect(appState.rotationMode) { applyRotationPreference() }

            val systemDark = isSystemInDarkTheme()
            val dark = when (appState.themeMode) {
                "dark" -> true
                "light" -> false
                else -> systemDark
            }
            TigerDuckTheme.setDarkMode(dark)

            TigerDuckAppTheme(darkTheme = dark, accentColor = appState.accentColor(dark)) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AppNavigation(
                            appState = appState,
                            analyticsLogger = analyticsLogger,
                            widgetStartRoute = widgetStartRoute.value,
                            onStartRouteConsumed = {
                                widgetStartRoute.value = null
                                // Clear the deep-link payload so a later onCreate
                                // (e.g. after rotation) doesn't re-navigate to the
                                // route the user already consumed.
                                intent?.let {
                                    it.data = null
                                    it.removeExtra("start_route")
                                    intent = it
                                }
                            },
                        )

                        // "An update is available" prompt — three actions:
                        // Update now (Play Store deep link), Later (7-day
                        // same-version cooldown), Skip this version
                        // (indefinite per-version suppression). Mounted at
                        // app root so a tab swap can't strand it.
                        UpdatePromptHost(updateChecker)

                        // "Your build is too old" — fires on a 410 from our
                        // backend, which is how the server retires an API
                        // version. Separate from UpdatePromptHost above:
                        // that one is an optional nudge while the app still
                        // works, this one means every backend call is now
                        // failing and only a new build fixes it.
                        UpdateRequiredHost()

                        // Held back while the wizard owns the screen — an
                        // upgrade re-runs it, and a dialog stacked on top of
                        // page 1 would be the first thing that user sees. The
                        // state stays set, so it opens the moment the wizard
                        // is done, which is also the better place for it.
                        whatsNewFlow.value?.takeIf { !appState.showOnboarding }?.let { flow ->
                            WhatsNewSheet(
                                flow = flow,
                                onDismiss = {
                                    whatsNewFlow.value = null
                                    // Record the seen versionCode only now: a
                                    // sheet dropped by a config-change
                                    // recreation before this runs is re-shown
                                    // on the next onCreate (issue #89). Any
                                    // dismissal counts, from any page.
                                    recordWhatsNewSeen(
                                        appPreferences.lastSeenWhatsNewVersionCode,
                                        BuildConfig.VERSION_CODE,
                                    )
                                },
                            )
                        }

                        // First-trigger opt-in prompts (e.g. flip-to-library):
                        // root-level so the prompt can surface over any tab the
                        // gesture fires from, independent of the nav back stack.
                        FirstTriggerPromptHost(firstTriggerPromptController)

                        // Operator-issued popup: rendered over whatever screen
                        // the user lands on after tapping the notification.
                        // Coordinator's dedupe set short-circuits replays.
                        ServerPushPopupHost(serverPushPopupCoordinator)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAcademicCalendar()
        liveActivityManager.refresh()
        updateChecker.resume(this)
        applyRotationPreference()
        refreshMoodleCredentials()
        // Retries a device registration that failed while the process stayed
        // warm. Whether one is due, and the consent and flavor gates, are
        // decided inside; a no-op on fdroid.
        fcmBootstrap.retryRegistrationIfDue()

        // School Mail has no push: returning to the app is one of its check points (spec §8.5).
        // Throttled to one per minute and single-flight inside MailChecker; a no-op when signed out.
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            mailChecker.check(org.ntust.app.tigerduck.mail.sync.CheckSource.FOREGROUND)
        }
    }

    /**
     * Re-check the school calendar on every resume.
     *
     * Cheap by design — the server answers 304 with no body when nothing
     * changed — and unconditional: this is the one backend call that is not
     * gated on sign-in, cloud sync or flavour, because suppressing class
     * reminders on a public holiday should not depend on any of them.
     *
     * A successful change re-runs the schedulers, since alarms up to ten
     * days out may now fall on a newly-published holiday.
     */
    private fun refreshAcademicCalendar() {
        lifecycleScope.launch {
            val before = academicCalendar.current().revision
            academicCalendar.refresh()
            if (academicCalendar.current().revision != before) {
                liveActivityManager.refresh()
            }
        }
    }

    private fun refreshMoodleCredentials() {
        if (!authTokenManager.isLoggedIn) return
        val token = moodleTokenService.currentToken() ?: return
        lifecycleScope.launch {
            runCatching { pushApiClient.updateCredentials(token) }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Foldable unfold / external display attach changes smallestScreenWidthDp.
        // Re-evaluate so "auto" mode flips to/from sensor as the form factor changes.
        if (appState.rotationMode == AppPreferences.ROTATION_MODE_AUTO) {
            applyRotationPreference()
        }
    }

    private fun applyRotationPreference() {
        val orientation = when (appState.rotationMode) {
            AppPreferences.ROTATION_MODE_ENABLED -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            AppPreferences.ROTATION_MODE_DISABLED -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> if (resources.configuration.smallestScreenWidthDp >= 600) {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
        if (requestedOrientation != orientation) {
            requestedOrientation = orientation
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep getIntent() in sync with the latest delivered intent so anything
        // that re-reads it (Compose recomposition, lifecycle observers) sees
        // the deep-link URI rather than the launcher MAIN intent.
        setIntent(intent)
        widgetStartRoute.value = resolveStartRoute(intent)
        handleServerPushIntent(intent)
    }

    /**
     * Branches `tigerduck://server-push/<nid>?title=...&body=...` deep links
     * (built by [org.ntust.app.tigerduck.push.FcmService.showServerPopupNotification])
     * into the popup coordinator instead of the NavHost. Unlike the
     * `announcement` host, server-push has no destination route — it pops an
     * AlertDialog over whatever screen the user lands on. The coordinator's
     * dedupe set guarantees a re-delivered intent (rotation, Recents tap)
     * doesn't re-show the same dialog.
     */
    private fun handleServerPushIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "tigerduck" || data.authority != "server-push") return
        // MainActivity is exported (it's the launcher), so any installed app
        // can fire an explicit intent at this deep-link path. The token is a
        // per-install secret embedded by FcmService into the PendingIntent's
        // extras; an external intent will lack it and is dropped silently.
        val token = intent.getStringExtra(ServerPushIntentToken.EXTRA_NAME)
        if (token != serverPushIntentToken.value) {
            intent.data = null
            setIntent(intent)
            return
        }
        val nid = data.pathSegments.firstOrNull() ?: return
        val title = data.getQueryParameter("title").orEmpty()
        val body = data.getQueryParameter("body").orEmpty()
        // Null the data immediately so a rotation/recreate (which re-runs
        // onCreate with the original intent) doesn't re-dispatch the same
        // payload — the coordinator's dedupe set absorbs the duplicate, but
        // its DataStore write isn't synchronous with this method, so a
        // fast recreate could race it. Belt-and-suspenders.
        intent.data = null
        setIntent(intent)
        lifecycleScope.launch {
            serverPushPopupCoordinator.request(
                ServerPopupRequest(
                    notificationId = nid,
                    title = title,
                    body = body,
                ),
            )
        }
    }

    /**
     * Map intent input — widget extras and `tigerduck://announcement/<id>`
     * deep links from a tapped FCM bulletin notification — onto a NavHost
     * route. `widgetStartRoute` then drives the LaunchedEffect that
     * navigates once Compose is ready.
     */
    private fun resolveStartRoute(intent: Intent?): String? {
        intent?.getStringExtra("start_route")?.let { return it }
        val data = intent?.data ?: return null
        if (data.scheme == "tigerduck" && data.host == "announcement") {
            val id = data.lastPathSegment?.toIntOrNull() ?: return null
            return "announcements/detail/$id"
        }
        return null
    }

    /**
     * Decides what the "What's new" sheet shows on this launch — see
     * [WhatsNewGate.plan]. After an upgrade: the feature pages of every
     * version since the last one seen, oldest first, then the running
     * version's summary. A fresh install records the current version and
     * shows nothing; a user upgrading from a build that predates the
     * last-seen pref has no record either but has completed onboarding, and
     * gets the running version's pages once. The debug "Replay What's new"
     * sentinel forces the newest registered version.
     *
     * Safe to call on every onCreate: when the sheet is shown the last-seen
     * versionCode is recorded on dismiss (not here), so a config-change
     * recreation re-runs this and re-shows a still-pending sheet instead of
     * dropping it permanently (issue #89). On such a recreation
     * [savedInstanceState] carries the page ids that were on screen, so the
     * same pages come back even if an answer the user gave changed whether a
     * page applies; the debug replay sentinel is only consumed on a genuine
     * process start, so rotating after tapping the debug row can't pop the
     * sheet mid-session.
     *
     * The catalog and `whatsnew.json` are only built and read when the plan
     * needs them, so the everyday launch — already on the version last seen —
     * costs a preference read and nothing more.
     */
    private fun resolveWhatsNew(savedInstanceState: Bundle?) {
        val current = BuildConfig.VERSION_CODE
        val lastSeen = appPreferences.lastSeenWhatsNewVersionCode
        val languageTag = resources.configuration.locales[0].toLanguageTag()
        val catalog by lazy { WhatsNewCatalog.pages(appState) }
        val summaries by lazy { whatsNewRepository.summaries(languageTag) }

        val plan = WhatsNewGate.plan(
            lastSeen = lastSeen,
            current = current,
            hasCompletedOnboarding = appPreferences.hasCompletedOnboarding,
            freshStart = savedInstanceState == null,
            pageVersions = { catalog.keys },
            summaryVersions = { summaries.keys },
            wasShowing = savedInstanceState?.containsKey(KEY_WHATS_NEW_PAGE_IDS) == true,
        )
        when (plan) {
            WhatsNewGate.Plan.Defer -> Unit

            // Nothing to show — record now so the lookup doesn't re-run on
            // every launch, and so the next upgrade only stacks the versions
            // after this one.
            WhatsNewGate.Plan.RecordOnly -> recordWhatsNewSeen(lastSeen, current)

            is WhatsNewGate.Plan.Show -> {
                val flow = WhatsNewFlow.from(
                    plan = plan,
                    catalog = catalog,
                    summaries = summaries,
                    restoredPageIds = savedInstanceState?.getStringArrayList(KEY_WHATS_NEW_PAGE_IDS),
                )
                whatsNewFlow.value = flow
                // Every page was filtered out by its "only if this applies"
                // check and there's no summary: same as nothing to show.
                if (flow == null) recordWhatsNewSeen(lastSeen, current)
            }
        }
    }

    /**
     * Stores [WhatsNewGate.recordedAfter]: the running versionCode, or the
     * newer one already on record after a downgrade. Skips the write when
     * nothing changes, which is every launch after the first on a version.
     */
    private fun recordWhatsNewSeen(lastSeen: Int, current: Int) {
        val recorded = WhatsNewGate.recordedAfter(lastSeen, current)
        if (recorded != lastSeen) appPreferences.lastSeenWhatsNewVersionCode = recorded
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        whatsNewFlow.value?.let {
            outState.putStringArrayList(KEY_WHATS_NEW_PAGE_IDS, ArrayList(it.pageIds))
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        // During onboarding, the dedicated permission page triggers the prompt
        // with context. Skip the bare auto-prompt on cold start until that's
        // done — including an upgrade re-run, which shows that page again.
        if (appState.showOnboarding) return
        if (appState.systemPermissions.shouldRequestNotificationsAtLaunch()) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun ServerPushPopupHost(coordinator: ServerPushPopupCoordinator) {
    val popup by coordinator.pending.collectAsStateWithLifecycle()
    popup?.let { req ->
        TigerDuckDialog(
            onDismissRequest = { coordinator.acknowledge() },
            title = req.title,
            message = req.body,
            confirmText = stringResource(android.R.string.ok),
            onConfirm = { coordinator.acknowledge() },
        )
    }
}

/**
 * Observes [UpdateChecker.pendingUpdate] and renders [UpdatePromptDialog]
 * when an update is awaiting the user's choice. Flavor-safe: on fdroid the
 * flow is permanently null, so the dialog never mounts.
 *
 * Pulls the hosting Activity from `LocalContext` because the "Update Now"
 * deep link needs an Activity context (FLAG_ACTIVITY_NEW_TASK is required
 * for the Play Store intent, and using the application context for that
 * silently no-ops on some OEM launchers).
 */
@Composable
private fun UpdatePromptHost(updateChecker: UpdateChecker) {
    val pending by updateChecker.pendingUpdate.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity ?: return
    if (pending != null) {
        UpdatePromptDialog(
            onUpdateNow = { updateChecker.onUpdateNow(activity) },
            onLater = { updateChecker.onLater() },
            onSkipThisVersion = { updateChecker.onSkipThisVersion() },
            onDismissRequest = { updateChecker.dismissPrompt() },
        )
    }
}

/**
 * Blocking-ish notice for a build the server no longer answers.
 *
 * Not a hard wall: much of the app is local — the class table, the time
 * machine, cached announcements — and locking a student out of their own
 * timetable would be worse than letting them read it while sync stays
 * broken. So there is no Cancel, but it can be dismissed once read, and it
 * returns on the next launch because [ApiVersionGate] stays latched.
 */
@Composable
private fun UpdateRequiredHost() {
    val retired by ApiVersionGate.isRetired.collectAsStateWithLifecycle()
    // Per-composition, not persisted: the gate never un-latches, so without
    // this the dialog would immediately re-show itself.
    var acknowledged by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    if (!retired || acknowledged) return

    AlertDialog(
        onDismissRequest = { acknowledged = true },
        title = { Text(stringResource(R.string.update_required_title)) },
        text = { Text(stringResource(R.string.update_required_message)) },
        confirmButton = {
            TextButton(onClick = {
                uriHandler.openUri(TIGERDUCK_WEBSITE_URL)
                acknowledged = true
            }) {
                Text(stringResource(R.string.update_required_action))
            }
        },
        dismissButton = {
            TextButton(onClick = { acknowledged = true }) {
                Text(stringResource(R.string.action_got_it))
            }
        },
    )
}

private const val TIGERDUCK_WEBSITE_URL = "https://tigerduck.app"

/** Page ids of a pending What's New sheet, kept across a config-change recreation. */
private const val KEY_WHATS_NEW_PAGE_IDS = "whatsNewPageIds"
