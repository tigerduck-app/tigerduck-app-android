package org.ntust.app.tigerduck.widget.receivers

import androidx.glance.appwidget.GlanceAppWidgetReceiver
import org.ntust.app.tigerduck.widget.BaseClassTableWidget
import org.ntust.app.tigerduck.widget.WidgetLayout

class NextClassLightWidget : BaseClassTableWidget(WidgetLayout.NextClass)
class NextClassLightWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = NextClassLightWidget()
}
