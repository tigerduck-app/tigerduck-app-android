package org.ntust.app.tigerduck.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class WidgetBoundaryReceiver : BroadcastReceiver() {

    @Inject lateinit var widgetUpdater: WidgetUpdater

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            try {
                widgetUpdater.updateAll()
            } finally {
                pending.finish()
            }
        }
    }
}
