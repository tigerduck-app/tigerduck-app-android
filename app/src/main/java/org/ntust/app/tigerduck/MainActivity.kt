package org.ntust.app.tigerduck

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.liveactivity.LiveActivityManager
import org.ntust.app.tigerduck.notification.BackgroundSyncWorker
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.navigation.AppNavigation
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var appState: AppState
    @Inject lateinit var liveActivityManager: LiveActivityManager
    @Inject lateinit var authService: AuthService

    private val widgetStartRoute = mutableStateOf<String?>(null)

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) liveActivityManager.refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        volumeControlStream = AudioManager.STREAM_NOTIFICATION

        requestNotificationPermissionIfNeeded()
        // Only schedule on the first Activity creation. WorkManager.UPDATE would
        // be idempotent, but re-enqueuing on every rotation/config change is
        // wasted work (and thrashes WorkManager's internal bookkeeping DB).
        if (savedInstanceState == null && authService.storedStudentId != null) {
            BackgroundSyncWorker.schedule(applicationContext)
        }

        widgetStartRoute.value = resolveStartRoute(intent)

        setContent {
            val systemDark = isSystemInDarkTheme()
            val dark = when (appState.themeMode) {
                "dark" -> true
                "light" -> false
                else -> systemDark
            }
            TigerDuckTheme.setDarkMode(dark)

            TigerDuckAppTheme(darkTheme = dark, accentColor = appState.accentColor(dark)) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavigation(appState = appState, widgetStartRoute = widgetStartRoute.value)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        liveActivityManager.refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
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
            return "announcement/$id"
        }
        return null
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
