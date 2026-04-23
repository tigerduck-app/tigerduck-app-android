package org.ntust.app.tigerduck.widget.receivers

import androidx.glance.appwidget.GlanceAppWidgetReceiver
import org.ntust.app.tigerduck.widget.BaseClassTableWidget
import org.ntust.app.tigerduck.widget.WidgetLayout

class WeekLightWidget : BaseClassTableWidget(WidgetLayout.Week, isDark = false)
class WeekLightWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = WeekLightWidget()
}
