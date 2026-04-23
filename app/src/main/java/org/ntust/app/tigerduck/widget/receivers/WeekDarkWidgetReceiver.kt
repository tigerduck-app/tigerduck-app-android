package org.ntust.app.tigerduck.widget.receivers

import androidx.glance.appwidget.GlanceAppWidgetReceiver
import org.ntust.app.tigerduck.widget.BaseClassTableWidget
import org.ntust.app.tigerduck.widget.WidgetLayout

class WeekDarkWidget : BaseClassTableWidget(WidgetLayout.Week, isDark = true)
class WeekDarkWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = WeekDarkWidget()
}
