package com.tigerduck.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.tigerduck.app.ui.AppState
import com.tigerduck.app.ui.navigation.AppNavigation
import com.tigerduck.app.ui.theme.TigerDuckAppTheme
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
            TigerDuckAppTheme(accentColor = appState.accentColor) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavigation(appState = appState)
                }
            }
        }
    }
}
