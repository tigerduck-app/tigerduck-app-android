package org.ntust.app.tigerduck.widget.content

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.appwidget.cornerRadius
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.screen.classtable.ClassTableThisWeek
import org.ntust.app.tigerduck.util.replaceIosArg
import org.ntust.app.tigerduck.widget.WidgetColors
import org.ntust.app.tigerduck.widget.WidgetState
import org.ntust.app.tigerduck.widget.scaled

/**
 * The Week grid's header, as iOS `WeekHeaderRow`: the term's week in the corner above the
 * periods, and each day's name, today's marked in the accent. With [showsDates], each day's date
 * under its name, the week running from today, today's date the one marked.
 */
@Composable
internal fun WeekHeaderRow(
    state: WidgetState,
    colors: WidgetColors,
    periodColWidth: Dp,
    height: Dp,
    bottomPad: Dp,
    showsDates: Boolean,
) {
    val context = LocalContext.current
    Row(modifier = GlanceModifier.fillMaxWidth().height(height).padding(bottom = bottomPad)) {
        Box(modifier = GlanceModifier.width(periodColWidth).fillMaxHeight(), contentAlignment = Alignment.Center) {
            state.termWeek?.let { week ->
                // "W" and the number, in every language, as the iPhone writes it.
                Text(
                    text = "W$week",
                    style = TextStyle(
                        color = ColorProvider(colors.onSurfaceVariant),
                        fontSize = 10.sp.scaled,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    ),
                    maxLines = 1,
                    modifier = GlanceModifier.semantics {
                        contentDescription = context.getString(R.string.a11y_class_table_week).replaceIosArg(1, week.toLong())
                    },
                )
            }
        }
        state.activeWeekdays.forEach { day ->
            Box(modifier = GlanceModifier.defaultWeight().fillMaxHeight(), contentAlignment = Alignment.Center) {
                val name = context.getString(WEEKDAY_SHORT[day - 1])
                val isToday = day == state.currentWeekday
                val date = state.today?.takeIf { showsDates }?.let { ClassTableThisWeek.columnDate(day, it) }
                if (date == null) {
                    DayMark(name, isToday, colors, 12.sp.scaled)
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = name,
                            style = TextStyle(
                                color = ColorProvider(if (isToday) colors.highlight else colors.onSurfaceVariant),
                                fontSize = 12.sp.scaled,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                            ),
                            maxLines = 1,
                        )
                        DayMark(date.dayOfMonth.toString(), isToday, colors, 11.sp.scaled)
                    }
                }
            }
        }
    }
}

/** [text] in the day's header: in the accent's capsule for today, else in the secondary ink. */
@Composable
private fun DayMark(text: String, isToday: Boolean, colors: WidgetColors, fontSize: androidx.compose.ui.unit.TextUnit) {
    val style = TextStyle(
        color = ColorProvider(if (isToday) colors.onAccentFill else colors.onSurfaceVariant),
        fontSize = fontSize,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
    if (!isToday) {
        Text(text = text, style = style, maxLines = 1)
        return
    }
    Box(
        modifier = GlanceModifier.accentBackground(colors, WidgetAccentMark.Badge).cornerRadius(9.dp.scaled)
            .padding(horizontal = 5.dp.scaled, vertical = 1.dp.scaled),
    ) {
        Text(text = text, style = style, maxLines = 1)
    }
}
