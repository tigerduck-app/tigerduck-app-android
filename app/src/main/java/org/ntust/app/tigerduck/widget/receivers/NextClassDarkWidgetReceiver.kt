package org.ntust.app.tigerduck.widget.receivers

import androidx.glance.appwidget.GlanceAppWidgetReceiver
import org.ntust.app.tigerduck.widget.BaseClassTableWidget
import org.ntust.app.tigerduck.widget.WidgetLayout

/**
 * Draws what [NextClassLightWidget] does. Hidden from the widget picker and kept only
 * so widgets placed from it before keep rendering: removing the receiver would
 * leave them broken on the home screen.
 */
class NextClassDarkWidget : BaseClassTableWidget(WidgetLayout.NextClass)
class NextClassDarkWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = NextClassDarkWidget()
}
