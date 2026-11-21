package org.ntust.app.tigerduck.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ClassWidgetEntryPoint {
    fun widgetUpdater(): WidgetUpdater
}

/**
 * The receiver of a class widget, Today's, Week's or Next Class's. Besides Glance's drawing of the
 * widgets an update names, it arms the next class boundary and the class hours' redraws
 * ([WidgetUpdater.armBoundaries]), as the system sends an update when a widget is placed: the app
 * armed them last for the widgets placed then, and a widget placed since, a Today or Week one
 * skipping its configuration on Android 12+ or a Next Class one, which has none, would keep its
 * progress and countdown as first drawn until the app next refreshes or a class starts or ends.
 *
 * Arming redraws nothing and sends no update, so the update [WidgetUpdater.updateAll] sends to the
 * placed widgets comes back here as one more arming and stops.
 */
abstract class ClassWidgetReceiver : GlanceAppWidgetReceiver() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // On the app's scope, not under goAsync(): Glance's update took this broadcast's one
        // pending result (or runs in a worker on phones where goAsync is broken), and its drawing
        // keeps the process up for longer than arming takes.
        EntryPointAccessors.fromApplication(context.applicationContext, ClassWidgetEntryPoint::class.java)
            .widgetUpdater()
            .requestArm()
    }
}
