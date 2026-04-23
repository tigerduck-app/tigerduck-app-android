package org.ntust.app.tigerduck.widget

import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import org.ntust.app.tigerduck.MainActivity
import org.ntust.app.tigerduck.widget.content.NextClassContent
import org.ntust.app.tigerduck.widget.content.TodayListContent
import org.ntust.app.tigerduck.widget.content.WeekGridContent

abstract class BaseClassTableWidget(
    private val layout: WidgetLayout,
    val isDark: Boolean,
) : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = WidgetDataLoader.load(context)
        val colors = if (isDark) WidgetTheme.Dark else WidgetTheme.Light
        val tapIntent = Intent(context, MainActivity::class.java)
            .putExtra("start_route", "classTable")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tapAction = actionStartActivity(tapIntent)
        provideContent {
            when (layout) {
                WidgetLayout.Week -> WeekGridContent(state, colors, tapAction)
                WidgetLayout.Today -> TodayListContent(state, colors, tapAction)
                WidgetLayout.NextClass -> NextClassContent(state, colors, tapAction)
            }
        }
    }
}
