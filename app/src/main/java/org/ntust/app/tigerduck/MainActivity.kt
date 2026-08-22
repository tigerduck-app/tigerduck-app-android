package org.ntust.app.tigerduck

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.analytics.AnalyticsLogger
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.liveactivity.LiveActivityManager
import org.ntust.app.tigerduck.notification.BackgroundSyncWorker
import org.ntust.app.tigerduck.serverpush.ServerPopupRequest
import org.ntust.app.tigerduck.serverpush.ServerPushIntentToken
import org.ntust.app.tigerduck.serverpush.ServerPushPopupCoordinator
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.component.TigerDuckDialog
import org.ntust.app.tigerduck.ui.firsttrigger.FirstTriggerPromptController
import org.ntust.app.tigerduck.ui.firsttrigger.FirstTriggerPromptHost
import org.ntust.app.tigerduck.ui.navigation.AppNavigation
import org.ntust.app.tigerduck.ui.screen.whatsnew.WhatsNewDialog
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import org.ntust.app.tigerduck.update.UpdateChecker
import org.ntust.app.tigerduck.ui.screen.update.UpdatePromptDialog
import org.ntust.app.tigerduck.data.model.WhatsNewContent
import org.ntust.app.tigerduck.update.WhatsNewGate
import org.ntust.app.tigerduck.update.WhatsNewRepository
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.network.MoodleTokenService
import org.ntust.app.tigerduck.push.PushApiClient
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var analyticsLogger: AnalyticsLogger

    @Inject
    lateinit var appState: AppState

    @Inject
    lateinit var liveActivityManager: LiveActivityManager

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

    private val widgetStartRoute = mutableStateOf<String?>(null)
    private val whatsNewContent = mutableStateOf<WhatsNewContent?>(null)

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) liveActivityManager.refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        volumeControlStream = AudioManager.STREAM_NOTIFICATION

        applyRotationPreference()
        requestNotificationPermissionIfNeeded()
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
        // recreations: the dialog's versionCode is recorded only once the user
        // dismisses it (see resolveWhatsNew), so re-deriving here re-shows a
        // dialog the user had not yet dismissed instead of dropping it
        // permanently on rotation (issue #89). freshStart keeps the debug
        // "Replay" sentinel from being consumed by a mere recreation.
        resolveWhatsNew(freshStart = savedInstanceState == null)

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

                        whatsNewContent.value?.let { content ->
                            WhatsNewDialog(
                                content = content,
                                onDismiss = {
                                    whatsNewContent.value = null
                                    // Record the seen versionCode only now: a
                                    // dialog dropped by a config-change
                                    // recreation before this runs is re-shown
                                    // on the next onCreate (issue #89).
                                    appPreferences.lastSeenWhatsNewVersionCode =
                                        BuildConfig.VERSION_CODE
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
        liveActivityManager.refresh()
        updateChecker.resume(this)
        applyRotationPreference()
        refreshMoodleCredentials()
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
     * Decides whether to show the "What's new" dialog. A fresh install records
     * the current version and shows nothing; upgrades show the dialog if
     * `whatsnew.json` has an entry. A user upgrading from a build that predates
     * the last-seen pref has no recorded versionCode either, but — unlike a
     * fresh install — has completed onboarding; that distinguishes the two so
     * real upgrades still get the dialog once. The debug "Replay What's new"
     * sentinel forces the newest authored entry.
     *
     * Safe to call on every onCreate: when a dialog is shown the last-seen
     * versionCode is recorded on dismiss (not here), so a config-change
     * recreation re-runs this and re-shows a still-pending dialog instead of
     * dropping it permanently (issue #89). [freshStart] is false for such
     * recreations; the debug replay sentinel is only consumed when it is true,
     * so rotating after tapping the debug row can't pop the dialog mid-session.
     */
    private fun resolveWhatsNew(freshStart: Boolean) {
        val current = BuildConfig.VERSION_CODE
        val lastSeen = appPreferences.lastSeenWhatsNewVersionCode
        val languageTag = resources.configuration.locales[0].toLanguageTag()

        // Debug "Replay What's new": show the newest authored entry even if
        // this build's versionCode predates it — whatsnew.json is usually
        // written ahead of the version bump. Only consume the sentinel on a
        // genuine process start; on a rotation/config-change recreation leave
        // it set so the replay fires on the *next* launch as the Settings row
        // promises, instead of popping the dialog the instant the device turns.
        if (lastSeen == AppPreferences.WHATS_NEW_REPLAY) {
            if (!freshStart) return
            val replay = whatsNewRepository.latestEntry(languageTag)
            whatsNewContent.value = replay
            if (replay == null) appPreferences.lastSeenWhatsNewVersionCode = current
            return
        }

        // No versionCode on record. A genuine fresh install shows nothing — a
        // new user has missed nothing. A user upgrading from a build that
        // predates this pref also has no record, but has completed onboarding;
        // fall through and show the current version's entry once.
        if (lastSeen == AppPreferences.WHATS_NEW_UNSET && !appPreferences.hasCompletedOnboarding) {
            appPreferences.lastSeenWhatsNewVersionCode = current
            return
        }

        val content = when {
            // UNSET here means a pre-feature upgrade (onboarding already done);
            // the normal gate only fires for a recorded older versionCode.
            lastSeen == AppPreferences.WHATS_NEW_UNSET ||
                    WhatsNewGate.shouldShow(lastSeen, current) ->
                whatsNewRepository.entryFor(current, languageTag)

            else -> null
        }
        whatsNewContent.value = content
        if (content == null) {
            // Nothing to show — record now so a missing entry does not
            // re-trigger the lookup on every launch. When a dialog *is* shown,
            // its onDismiss records the versionCode instead.
            appPreferences.lastSeenWhatsNewVersionCode = current
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        // During onboarding, the dedicated permission page triggers the prompt
        // with context. Skip the bare auto-prompt on cold start until that's done.
        if (!appState.hasCompletedOnboarding) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
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
    pending?.let { p ->
        UpdatePromptDialog(
            pending = p,
            onUpdateNow = { updateChecker.onUpdateNow(activity) },
            onLater = { updateChecker.onLater() },
            onSkipThisVersion = { updateChecker.onSkipThisVersion() },
            onDismissRequest = { updateChecker.dismissPrompt() },
        )
    }
}
