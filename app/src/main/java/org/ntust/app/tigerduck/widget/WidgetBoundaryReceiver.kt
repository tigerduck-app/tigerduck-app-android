package org.ntust.app.tigerduck.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class WidgetBoundaryReceiver : BroadcastReceiver() {

    @Inject lateinit var widgetUpdater: WidgetUpdater

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        widgetUpdater.requestBoundaryUpdate {
            pending.finish()
        }
    }
}
