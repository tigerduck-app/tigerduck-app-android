package org.ntust.app.tigerduck.widget.receivers

import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import org.ntust.app.tigerduck.widget.BaseClassTableWidget
import org.ntust.app.tigerduck.widget.WidgetLayout

class TodayLightWidget : BaseClassTableWidget(WidgetLayout.Today, SizeMode.Single)
class TodayLightWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = TodayLightWidget()
}
