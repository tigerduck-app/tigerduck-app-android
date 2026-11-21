package org.ntust.app.tigerduck.widget.receivers

import org.ntust.app.tigerduck.widget.BaseClassTableWidget
import org.ntust.app.tigerduck.widget.ClassWidgetReceiver
import org.ntust.app.tigerduck.widget.WidgetLayout

class WeekLightWidget : BaseClassTableWidget(WidgetLayout.Week)
class WeekLightWidgetReceiver : ClassWidgetReceiver() {
    override val glanceAppWidget = WeekLightWidget()
}
