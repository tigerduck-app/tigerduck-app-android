package org.ntust.app.tigerduck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.navigation.AppNavigation
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var appState: AppState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val systemDark = isSystemInDarkTheme()
            val dark = when (appState.themeMode) {
                "dark" -> true
                "light" -> false
                else -> systemDark
            }
            SideEffect { TigerDuckTheme.setDarkMode(dark) }

            TigerDuckAppTheme(darkTheme = dark, accentColor = appState.accentColor(dark)) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavigation(appState = appState)
                }
            }
        }
    }
}
