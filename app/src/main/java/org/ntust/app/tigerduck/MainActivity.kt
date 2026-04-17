package org.ntust.app.tigerduck

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.liveactivity.LiveActivityManager
import org.ntust.app.tigerduck.notification.HomeworkRefreshWorker
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.navigation.AppNavigation
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var appState: AppState
    @Inject lateinit var liveActivityManager: LiveActivityManager
    @Inject lateinit var authService: AuthService

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) liveActivityManager.refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestNotificationPermissionIfNeeded()
        if (authService.storedStudentId != null) {
            HomeworkRefreshWorker.schedule(applicationContext)
        }

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
                    AppNavigation(appState = appState)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        liveActivityManager.refresh()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
