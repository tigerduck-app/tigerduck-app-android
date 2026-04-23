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
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.ntust.app.tigerduck.MainActivity
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.widget.content.NextClassContent
import org.ntust.app.tigerduck.widget.content.TodayListContent
import org.ntust.app.tigerduck.widget.content.WeekGridContent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetThemeEntryPoint {
    fun appPreferences(): AppPreferences
}

abstract class BaseClassTableWidget(
    private val layout: WidgetLayout,
) : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = prefs(context)
        val tapIntent = Intent(context, MainActivity::class.java)
            .putExtra("start_route", "classTable")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tapAction = actionStartActivity(tapIntent)
        provideContent {
            // Reactive tick read from Glance preferences — bumping it from
            // WidgetUpdater forces this composition to re-run, which in turn
            // re-launches the loader below AND re-reads theme/accent prefs.
            // provideGlance itself only runs once per Glance session, so any
            // prefs-derived state has to live inside provideContent.
            val tick = currentState(WidgetState.TickKey) ?: 0L
            val isDark = resolveWidgetIsDark(prefs, context)
            val accent = resolveWidgetAccentColor(prefs, isDark)
            val colors = (if (isDark) WidgetTheme.Dark else WidgetTheme.Light)
                .copy(highlight = accent)

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

    private fun prefs(context: Context): AppPreferences =
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetThemeEntryPoint::class.java,
        ).appPreferences()
}
