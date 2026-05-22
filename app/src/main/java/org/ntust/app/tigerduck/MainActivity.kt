package org.ntust.app.tigerduck

import android.Manifest
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.liveactivity.LiveActivityManager
import org.ntust.app.tigerduck.notification.BackgroundSyncWorker
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.navigation.AppNavigation
import org.ntust.app.tigerduck.ui.screen.whatsnew.WhatsNewDialog
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import org.ntust.app.tigerduck.update.UpdateChecker
import org.ntust.app.tigerduck.update.UpdateInstallSnackbar
import org.ntust.app.tigerduck.update.WhatsNewContent
import org.ntust.app.tigerduck.update.WhatsNewGate
import org.ntust.app.tigerduck.update.WhatsNewRepository
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

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
        }

        widgetStartRoute.value = resolveStartRoute(intent)

        // Update prompt + "what's new" — only on the first creation, not on
        // rotation/config-change recreations (issue #89).
        if (savedInstanceState == null) {
            updateChecker.maybePromptForUpdate(this)
            resolveWhatsNew()
        }

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

                        // App-global snackbar host for the in-app update
                        // "ready to install" prompt (issue #89). The app's
                        // other snackbar hosts are per-screen; this one
                        // outlives navigation.
                        val updateSnackbarHostState = remember { SnackbarHostState() }
                        SnackbarHost(
                            updateSnackbarHostState,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                        UpdateInstallSnackbar(updateChecker, updateSnackbarHostState)

                        whatsNewContent.value?.let { content ->
                            WhatsNewDialog(
                                content = content,
                                onDismiss = { whatsNewContent.value = null },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        liveActivityManager.refresh()
        updateChecker.resume(this)
        // Pref may have changed in Settings (which itself can run while
        // landscape-locked). Re-apply on every resume so the new choice
        // takes effect without needing an Activity recreate.
        applyRotationPreference()
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
     * Decides whether to show the "What's new" dialog. Fresh installs
     * (sentinel last-seen versionCode) silently record the current version and
     * show nothing; upgrades show the dialog if `whatsnew.json` has an entry.
     */
    private fun resolveWhatsNew() {
        val current = BuildConfig.VERSION_CODE
        val lastSeen = appPreferences.lastSeenWhatsNewVersionCode
        if (lastSeen == AppPreferences.WHATS_NEW_UNSET) {
            appPreferences.lastSeenWhatsNewVersionCode = current
            return
        }
        if (WhatsNewGate.shouldShow(lastSeen, current)) {
            val languageTag = resources.configuration.locales[0].toLanguageTag()
            whatsNewContent.value = whatsNewRepository.entryFor(current, languageTag)
        }
        // Record regardless of whether an entry existed, so a missing entry
        // does not re-trigger the lookup on every launch.
        appPreferences.lastSeenWhatsNewVersionCode = current
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
