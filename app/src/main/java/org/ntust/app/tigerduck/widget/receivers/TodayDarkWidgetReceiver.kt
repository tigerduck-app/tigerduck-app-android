package org.ntust.app.tigerduck.widget.receivers

import androidx.glance.appwidget.GlanceAppWidgetReceiver
import org.ntust.app.tigerduck.widget.BaseClassTableWidget
import org.ntust.app.tigerduck.widget.WidgetLayout

class TodayDarkWidget : BaseClassTableWidget(WidgetLayout.Today)
class TodayDarkWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = TodayDarkWidget()
}
