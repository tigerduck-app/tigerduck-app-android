package org.ntust.app.tigerduck.widget.receivers

import androidx.glance.appwidget.GlanceAppWidgetReceiver
import org.ntust.app.tigerduck.widget.BaseClassTableWidget
import org.ntust.app.tigerduck.widget.WidgetLayout

/**
 * Draws what [WeekLightWidget] does. Hidden from the widget picker and kept only
 * so widgets placed from it before keep rendering: removing the receiver would
 * leave them broken on the home screen.
 */
class WeekDarkWidget : BaseClassTableWidget(WidgetLayout.Week)
class WeekDarkWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = WeekDarkWidget()
}
