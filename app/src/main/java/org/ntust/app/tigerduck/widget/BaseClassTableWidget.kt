package org.ntust.app.tigerduck.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.unit.ColorProvider
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
        val colors = if (isDark) WidgetTheme.Dark else WidgetTheme.Light
        val tapIntent = Intent(context, MainActivity::class.java)
            .putExtra("start_route", "classTable")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tapAction = actionStartActivity(tapIntent)
        provideContent {
            // Reactive tick read from Glance preferences — bumping it from
            // WidgetUpdater forces this composition to re-run, which in turn
            // re-launches the loader below. provideGlance itself only runs
            // once per Glance session, so the data load cannot live there.
            val tick = currentState(WidgetState.TickKey) ?: 0L
            var state by remember { mutableStateOf<WidgetState?>(null) }
            LaunchedEffect(tick) {
                state = WidgetDataLoader.load(context)
            }
            val s = state
            if (s == null) {
                Box(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(ColorProvider(colors.background)),
                ) {}
            } else {
                when (layout) {
                    WidgetLayout.Week -> WeekGridContent(s, colors, tapAction)
                    WidgetLayout.Today -> TodayListContent(s, colors, tapAction)
                    WidgetLayout.NextClass -> NextClassContent(s, colors, tapAction)
                }
            }
        }
    }
}
