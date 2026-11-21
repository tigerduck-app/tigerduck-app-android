package org.ntust.app.tigerduck.widget.receivers

import org.ntust.app.tigerduck.widget.BaseClassTableWidget
import org.ntust.app.tigerduck.widget.ClassWidgetReceiver
import org.ntust.app.tigerduck.widget.WidgetLayout

class NextClassLightWidget : BaseClassTableWidget(WidgetLayout.NextClass)
class NextClassLightWidgetReceiver : ClassWidgetReceiver() {
    override val glanceAppWidget = NextClassLightWidget()
}
