package org.ntust.app.tigerduck.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.ntust.app.tigerduck.wear.complication.ComplicationUpdateWorker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WearApp() }
        ComplicationUpdateWorker.ensureScheduled(this)
    }
}
