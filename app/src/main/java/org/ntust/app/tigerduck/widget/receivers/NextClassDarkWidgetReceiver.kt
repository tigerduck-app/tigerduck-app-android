package org.ntust.app.tigerduck.widget.receivers

import androidx.glance.appwidget.GlanceAppWidgetReceiver
import org.ntust.app.tigerduck.widget.BaseClassTableWidget
import org.ntust.app.tigerduck.widget.WidgetLayout

class NextClassDarkWidget : BaseClassTableWidget(WidgetLayout.NextClass, isDark = true)
class NextClassDarkWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = NextClassDarkWidget()
}
