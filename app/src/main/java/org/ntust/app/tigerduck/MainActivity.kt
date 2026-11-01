package org.ntust.app.tigerduck

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.analytics.AnalyticsLogger
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.library.LibraryQrShortcut
import org.ntust.app.tigerduck.liveactivity.LiveActivityManager
import org.ntust.app.tigerduck.mail.MailAccount
import org.ntust.app.tigerduck.mail.MailRoutes
import org.ntust.app.tigerduck.mail.store.HeldSendStore
import org.ntust.app.tigerduck.network.ApiVersionGate
import org.ntust.app.tigerduck.network.MoodleTokenService
import org.ntust.app.tigerduck.network.NetworkChecker
import org.ntust.app.tigerduck.network.tls.PinManager
import org.ntust.app.tigerduck.notification.BackgroundSyncWorker
import org.ntust.app.tigerduck.push.FcmBootstrap
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.review.InAppReview
import org.ntust.app.tigerduck.review.ReviewPrompt
import org.ntust.app.tigerduck.serverpush.ServerPopupRequest
import org.ntust.app.tigerduck.serverpush.ServerPushIntentToken
import org.ntust.app.tigerduck.serverpush.ServerPushPopupCoordinator
import org.ntust.app.tigerduck.ui.AppProcessing
import org.ntust.app.tigerduck.ui.AppScale
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.BrowserPreference
import org.ntust.app.tigerduck.ui.component.AppProcessingHost
import org.ntust.app.tigerduck.ui.component.PinMismatchHost
import org.ntust.app.tigerduck.ui.component.TigerDuckDialog
import org.ntust.app.tigerduck.ui.firsttrigger.FirstTriggerPromptController
import org.ntust.app.tigerduck.ui.firsttrigger.FirstTriggerPromptHost
import org.ntust.app.tigerduck.ui.firsttrigger.firstTriggerPromptMayShow
import org.ntust.app.tigerduck.ui.navigation.AppNavigation
import org.ntust.app.tigerduck.ui.navigation.Screen
import org.ntust.app.tigerduck.ui.openLink
import org.ntust.app.tigerduck.ui.screen.classtable.sharing.ClassTableImportViewModel
import org.ntust.app.tigerduck.ui.screen.update.UpdatePromptDialog
import org.ntust.app.tigerduck.ui.screen.whatsnew.WhatsNewCatalog
import org.ntust.app.tigerduck.ui.screen.whatsnew.WhatsNewFlow
import org.ntust.app.tigerduck.ui.screen.whatsnew.WhatsNewSheet
import org.ntust.app.tigerduck.ui.screen.whatsnew.ranRelease23x
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import org.ntust.app.tigerduck.update.UpdateChecker
import org.ntust.app.tigerduck.update.WhatsNewGate
import org.ntust.app.tigerduck.update.WhatsNewRepository
import org.ntust.app.tigerduck.widget.LibraryShortcutWidget
import org.ntust.app.tigerduck.debug.PretendMissingApps

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
    lateinit var courseChangeStore: org.ntust.app.tigerduck.coursechanges.CourseChangeStore

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
    lateinit var appProcessing: AppProcessing

    @Inject
    lateinit var pinManager: PinManager

    @Inject
    lateinit var networkChecker: NetworkChecker

    @Inject
    lateinit var fcmBootstrap: FcmBootstrap

    @Inject
    lateinit var reviewPrompt: ReviewPrompt

    @Inject
    lateinit var inAppReview: InAppReview

    @Inject
    lateinit var mailChecker: org.ntust.app.tigerduck.mail.sync.MailChecker

    @Inject
    lateinit var libraryReturnSignIn: org.ntust.app.tigerduck.library.LibraryReturnSignIn

    @Inject
    lateinit var libraryPagePresence: org.ntust.app.tigerduck.library.LibraryPagePresence

    @Inject
    lateinit var heldShareLink: HeldShareLink

    @Inject
    lateinit var heldSends: HeldSendStore

    @Inject
    lateinit var mailAccount: MailAccount

    /** The display and text sizes this Activity was made at; see [AppScale]. */
    private var appScale = AppScale.DEFAULT

    /** The phone's configuration it was made from, to tell when a change it handles leaves [appScale] stale. */
    private var attachedConfiguration: Configuration? = null

    private val widgetStartRoute = mutableStateOf<String?>(null)
    private val whatsNewFlow = mutableStateOf<WhatsNewFlow?>(null)

    // The same activity-scoped instance MainNavigation's hiltViewModel() returns, which shows it.
    private val classTableImport: ClassTableImportViewModel by viewModels()

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            appState.systemPermissions.recordLaunchPromptResult(granted)
            if (granted) liveActivityManager.refresh()
        }

    // The sizes picked on the Display and text size page, over the phone's
    // own. Read from the file here, before Hilt has injected anything.
    override fun attachBaseContext(newBase: Context) {
        appScale = AppScale.stored(newBase)
        attachedConfiguration = Configuration(newBase.resources.configuration)
        super.attachBaseContext(appScale.wrap(newBase))
    }

    /** In debug builds, as a phone without the apps the developer settings mark missing would: see [PretendMissingApps]. */
    override fun startActivity(intent: Intent, options: Bundle?) {
        super.startActivity(PretendMissingApps.filter(intent, packageName, packageManager), options)
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
        // Here rather than at the app's start: the Activity has the app's own language.
        LibraryQrShortcut.publish(this)
        // Only schedule on the first Activity creation. WorkManager.UPDATE would
        // be idempotent, but re-enqueuing on every rotation/config change is
        // wasted work (and thrashes WorkManager's internal bookkeeping DB).
        if (savedInstanceState == null && authService.storedStudentId != null) {
            BackgroundSyncWorker.schedule(applicationContext)
        }

        widgetStartRoute.value = resolveStartRoute(intent, restored = savedInstanceState != null)
        handleServerPushIntent(intent)
        openSharedClassTable(intent, restored = savedInstanceState != null)
        // On every creation, a configuration change's included: a lookup the change cut short is
        // asked again, and a mail a composer has open is not offered (HeldSendStore.orphan).
        reopenHeldMail()
        // A share link held while no one could import it opens once someone signs in and onboarding
        // is finished, in either order. Only in the Activity on screen: another one, stopped in
        // another app's task, would take it and open its import where no one sees it.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(authService.authState, snapshotFlow { appState.showOnboarding }) { signedIn, onboarding ->
                    signedIn && !onboarding
                }.collect { mayImport -> heldShareLink.release(mayImport)?.let(::importSharedClassTable) }
            }
        }

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
            // A new display or text size, or a reset to the phone's own, takes
            // a new Activity: the sizes are its configuration.
            val pickedScale = appState.appScale
            LaunchedEffect(pickedScale) { if (pickedScale != appScale) recreate() }

            val systemDark = isSystemInDarkTheme()
            val dark = when (appState.themeMode) {
                "dark" -> true
                "light" -> false
                else -> systemDark
            }
            TigerDuckTheme.setDarkMode(dark)

            TigerDuckAppTheme(
                darkTheme = dark,
                accentColor = appState.accentColor(dark),
                onAccentColor = appState.accentOnColor(dark),
                accentGradient = appState.accentGradient(dark),
                useDynamicColor = appState.usesWallpaperColors,
                visualPreset = appState.visualPreset,
            ) {
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
                                    it.removeExtra(StartRoute.EXTRA)
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
                        // root-level so the prompt can surface over any tab,
                        // independent of the nav back stack. A pending one
                        // waits for What's New and the update prompt to close,
                        // as on the iPhone, rather than stacking on them, and
                        // the flip one for the Library page to be left.
                        val updatePending by updateChecker.pendingUpdate.collectAsStateWithLifecycle()
                        val pendingPrompt by firstTriggerPromptController.pending.collectAsStateWithLifecycle()
                        val libraryPageShown by libraryPagePresence.shown.collectAsStateWithLifecycle()
                        if (firstTriggerPromptMayShow(
                                whatsNewShowing = whatsNewFlow.value != null,
                                updatePromptShowing = updatePending != null,
                                pending = pendingPrompt?.key,
                                libraryPageShown = libraryPageShown,
                            )
                        ) {
                            FirstTriggerPromptHost(firstTriggerPromptController)
                        }

                        // The one Play Store rating ask (issue #332), at a
                        // moment ReviewPrompt picks and, like the prompt
                        // above, not over another prompt: the root-level
                        // ones here, AppNavigation's permission warning,
                        // reset dialog and sign-in sheet, the retired-API
                        // dialog, and the processing spinner. A dialog a page
                        // owns is the page's to hold the moment back for.
                        val popupPending by serverPushPopupCoordinator.pending.collectAsStateWithLifecycle()
                        val permissionWarning by appState.systemPermissions.warningShowing.collectAsStateWithLifecycle()
                        val needsReset by appState.needsUserReset.collectAsStateWithLifecycle()
                        val apiRetired by ApiVersionGate.isRetired.collectAsStateWithLifecycle()
                        val processingHolds by appProcessing.holds.collectAsStateWithLifecycle()
                        val pinPrompt by pinManager.prompts.pending.collectAsStateWithLifecycle()
                        ReviewPromptHost(
                            reviewPrompt = reviewPrompt,
                            inAppReview = inAppReview,
                            otherPromptShowing = whatsNewFlow.value != null || updatePending != null ||
                                pendingPrompt != null || popupPending != null || permissionWarning ||
                                needsReset || apiRetired || appState.ntustSignInSheetRequested ||
                                processingHolds > 0 || pinPrompt != null,
                        )

                        // Operator-issued popup: rendered over whatever screen
                        // the user lands on after tapping the notification.
                        // Coordinator's dedupe set short-circuits replays.
                        ServerPushPopupHost(serverPushPopupCoordinator)

                        // A school site's certificate stopped matching its pins (TLS pins
                        // spec, A6): asked here, in the foreground, whichever request met it.
                        PinMismatchHost(pinManager.prompts)

                        // "Processing… Please don't close the app.": work the
                        // user waits on, such as the sign-in a Moodle page
                        // opened in the browser waits for. Last, so it comes
                        // up over anything above.
                        AppProcessingHost(appProcessing, questionShowing = pinPrompt != null)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The library sign-in state read again, and a sign-in with the stored
        // credentials once the day-long token has run out.
        lifecycleScope.launch { libraryReturnSignIn.onAppResumed() }
        refreshAcademicCalendar()
        // The school's course changes, public as the calendar is; one conditional GET.
        lifecycleScope.launch { courseChangeStore.refresh() }
        liveActivityManager.refresh()
        updateChecker.resume(this)
        applyRotationPreference()
        refreshMoodleCredentials()
        // Signs in to the backend again after a sign-in that failed or a
        // session it dropped. Does nothing with a session, without an account,
        // or within five minutes of a failed sign-in. Not offline, where it
        // could only fail and start that wait.
        if (networkChecker.isAvailable()) {
            lifecycleScope.launch { authTokenManager.signInIfSignedOut() }
        }
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
        // The screen's size in dp laid over this one is the old screen's.
        if (attachedConfiguration?.let { appScale.isStale(it, newConfig) } == true) {
            recreate()
            return
        }
        // A language change lands here, as the manifest handles locale: relabel the shortcut.
        LibraryQrShortcut.publish(this)
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
        widgetStartRoute.value = resolveStartRoute(intent, restored = false)
        handleServerPushIntent(intent)
        openSharedClassTable(intent, restored = false)
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
     * The NavHost route [intent] asks for; see [linkedStartRoute]. `widgetStartRoute` then drives
     * the LaunchedEffect that navigates once Compose is ready. An assignment link, from a
     * reminder, the due-soon Live Update or another app, opens the assignment's page in the app.
     * It opens once per delivery: the link is dropped from the intent, and an Activity rebuilt from
     * saved state ([restored]) or relaunched from Recents does not open it again. The page itself
     * shows only an assignment this account has cached, so a link from elsewhere can open nothing
     * but the user's own homework.
     */
    private fun resolveStartRoute(intent: Intent?, restored: Boolean): String? {
        intent ?: return null
        val hadData = intent.data != null
        val route = linkedStartRoute(intent, restored)
        if (hadData && intent.data == null) setIntent(intent)
        return route
    }

    /**
     * Opens the composer again for a mail the system ended the app over while its send was held
     * for Undo or under way (tigerduck-app#555), for the student to review: it is never sent on its
     * own. The only place that does: a composer the system rebuilds from saved state opens empty
     * and gives way to this one. A link the app was opened with goes first, and the mail waits for
     * the next creation.
     */
    private fun reopenHeldMail() {
        lifecycleScope.launch {
            // The account's address and the kept mail are reads of the disk.
            val route = withContext(Dispatchers.IO) { heldMailRoute(heldSends, mailAccount.ownAddress) } ?: return@launch
            if (widgetStartRoute.value == null) widgetStartRoute.value = route
        }
    }

    /**
     * Opens the class table with the import of the share code a link carries: an App Link from
     * the camera, Google Lens, another QR app or a tapped link, or the share page's button. It
     * takes the steps a scanned code does, and replaces an import already open. Only for someone
     * signed in who has finished onboarding: before that the link is held ([HeldShareLink]), and
     * opens then.
     *
     * Once per delivery, as [resolveStartRoute]: see [takeSharedClassTableCode]. The import
     * itself lives in [classTableImport], so a configuration change keeps it on screen.
     */
    private fun openSharedClassTable(intent: Intent?, restored: Boolean) {
        intent ?: return
        val hadData = intent.data != null
        val text = takeSharedClassTableCode(intent, restored)
        if (hadData && intent.data == null) setIntent(intent)
        text ?: return
        val mayImport = authService.authState.value && !appState.showOnboarding
        heldShareLink.arrived(text, mayImport)?.let(::importSharedClassTable)
    }

    private fun importSharedClassTable(text: String) {
        // As a widget's tap opens it, on the user's own table; the import, once saved, shows the
        // person it added.
        widgetStartRoute.value = Screen.ClassTable.route
        classTableImport.openLink(text)
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
        // Before this run's record replaces it: 2.3.x's What's New asked the Mail question.
        if (ranRelease23x(lastSeen)) appPreferences.mailBarQuestionAsked = true
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
 * Pulls the hosting Activity from `LocalActivity` because the "Update Now"
 * deep link needs an Activity context (FLAG_ACTIVITY_NEW_TASK is required
 * for the Play Store intent, and using the application context for that
 * silently no-ops on some OEM launchers).
 */
@Composable
private fun UpdatePromptHost(updateChecker: UpdateChecker) {
    val pending by updateChecker.pendingUpdate.collectAsStateWithLifecycle()
    val activity = LocalActivity.current ?: return
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
    val context = LocalContext.current
    if (!retired || acknowledged) return

    AlertDialog(
        onDismissRequest = { acknowledged = true },
        title = { Text(stringResource(R.string.update_required_title)) },
        text = { Text(stringResource(R.string.update_required_message)) },
        confirmButton = {
            TextButton(onClick = {
                openLink(context, AppUrls.WEBSITE, BrowserPreference.SYSTEM)
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

/**
 * The assignment id [intent]'s `tigerduck://assignment/<id>` link asks to open
 * now, or null. The link is taken off [intent] either way, so it opens once
 * per delivery; and none opens for an Activity rebuilt from saved state
 * ([restored]) or relaunched from Recents, which hand back the intent it was
 * first started with. A link of any other kind is left on [intent].
 */
internal fun takeLinkedAssignmentId(intent: Intent, restored: Boolean): String? {
    val link = AppLink.parse(intent.data) as? AppLink.Assignment ?: return null
    intent.data = null
    val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
    return link.id.takeIf { !restored && !fromHistory }
}

/**
 * The composer to open for [owner]'s mail kept for its send that no composer of this process has
 * open, the oldest, or null: none kept, or no one signed in to the mail.
 */
internal fun heldMailRoute(store: HeldSendStore, owner: String?): String? =
    owner?.let(store::orphan)?.let(MailRoutes::reopen)

/**
 * The NavHost route [intent] opens: the page of the assignment a `tigerduck://assignment/<id>`
 * link names, once per delivery and not for an Activity [restored] from saved state (see
 * [takeLinkedAssignmentId]), else whatever [startRouteOf] makes of it.
 */
internal fun linkedStartRoute(intent: Intent, restored: Boolean): String? {
    takeLinkedAssignmentId(intent, restored)?.let { return Screen.Assignment.route(it) }
    return startRouteOf(intent)
}

/**
 * Map intent input — the [StartRoute] extra of the app's own notifications,
 * and the `tigerduck://` links of [AppLink] — onto a NavHost route. An intent
 * with a `tigerduck://` link, or one a browser marks
 * [Intent.CATEGORY_BROWSABLE], opens that link alone: the app's own senders
 * put neither on an intent with the extra. A URI of any other scheme does not
 * count as a link, as Glance gives a widget's intent that has no data a
 * `glance-action:` one of its own. An extra naming any other route is
 * dropped.
 */
internal fun startRouteOf(intent: Intent?): String? {
    intent ?: return null
    val fromLink = intent.data?.scheme == AppLink.SCHEME || intent.hasCategory(Intent.CATEGORY_BROWSABLE)
    if (!fromLink) StartRoute.accepted(intent.getStringExtra(StartRoute.EXTRA))?.let { return it }
    return AppLink.parse(intent.data)?.let(::startRouteFor)
}

/**
 * The NavHost route [link] opens. Library goes through the widget's sentinel
 * so the library feature switch is read when the link arrives.
 */
internal fun startRouteFor(link: AppLink): String? = when (link) {
    AppLink.Library -> LibraryShortcutWidget.ROUTE_SENTINEL
    AppLink.ClassTable -> Screen.ClassTable.route
    is AppLink.Announcement -> Screen.AnnouncementDetail.route(link.id)
    is AppLink.Assignment -> Screen.Assignment.route(link.id)
}

/** Page ids of a pending What's New sheet, kept across a config-change recreation. */
private const val KEY_WHATS_NEW_PAGE_IDS = "whatsNewPageIds"

/**
 * Launches Play's in-app review when [ReviewPrompt] says the moment has come
 * (issue #332), and not over another prompt: Play's sheet on top of What's
 * new, the update prompt, a first-trigger question or an operator popup
 * would be two asks at once. Held back, the ask waits for them to close, as
 * the first-trigger prompt does. On fdroid [InAppReview] launches nothing
 * and says so, which leaves the ask unrecorded.
 */
@Composable
private fun ReviewPromptHost(
    reviewPrompt: ReviewPrompt,
    inAppReview: InAppReview,
    otherPromptShowing: Boolean,
) {
    val pending by reviewPrompt.pending.collectAsStateWithLifecycle()
    val activity = LocalActivity.current ?: return
    if (!pending || otherPromptShowing) return
    // Keyed on nothing: a recomposition while Play is being asked for the
    // flow must not ask twice. Leaving composition — the ask withdrawn, or
    // another prompt opened — cancels that request before a launch; and the
    // request is a round trip, so ReviewPrompt re-checks its gates before
    // the flow it brought back is started.
    LaunchedEffect(Unit) {
        val flow = inAppReview.request()
        if (flow == null) {
            reviewPrompt.refused()
        } else {
            // A dialog, a sheet or a system prompt opened meanwhile is a
            // window of its own and holds the focus, whatever page owns it;
            // the page's own state says whether it is still on screen.
            reviewPrompt.launchIfStillCalm(windowFocused = { activity.hasWindowFocus() }) {
                inAppReview.launch(activity, flow)
            }
        }
    }
}
