package org.ntust.app.tigerduck.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class WidgetBoundaryReceiver : BroadcastReceiver() {

    @Inject
    lateinit var widgetUpdater: WidgetUpdater

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        try {
            widgetUpdater.requestBoundaryUpdate {
                pending.finish()
            }
        } catch (t: Throwable) {
            // Synchronous failure (e.g., DI not initialized) would otherwise
            // leak the goAsync token and stall the broadcast pipe.
            pending.finish()
            throw t
        }
    }
}
