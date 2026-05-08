package org.ntust.app.tigerduck.widget.receivers

import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import org.ntust.app.tigerduck.widget.BaseClassTableWidget
import org.ntust.app.tigerduck.widget.WidgetLayout

class TodayDarkWidget : BaseClassTableWidget(WidgetLayout.Today, SizeMode.Single)
class TodayDarkWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = TodayDarkWidget()
}
