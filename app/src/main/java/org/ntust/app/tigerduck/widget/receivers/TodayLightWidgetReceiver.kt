package org.ntust.app.tigerduck.widget.receivers

import org.ntust.app.tigerduck.widget.BaseClassTableWidget
import org.ntust.app.tigerduck.widget.ClassWidgetReceiver
import org.ntust.app.tigerduck.widget.WidgetLayout

class TodayLightWidget : BaseClassTableWidget(WidgetLayout.Today)
class TodayLightWidgetReceiver : ClassWidgetReceiver() {
    override val glanceAppWidget = TodayLightWidget()
}
