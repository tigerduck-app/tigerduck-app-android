package org.ntust.app.tigerduck.ui.screen.emptyclassroom

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.unit.sp
import org.ntust.app.tigerduck.core.strings.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.emptyclassroom.EmptyClassroomSchedule
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The finder's two signal colours: green for a free room, orange for a booking. Fills use the
 * system greens and oranges; text gets the darker light-mode variant, as the bright ones are
 * under 3:1 on white.
 */
internal object FinderColors {
    val freeFill: Color @Composable @ReadOnlyComposable get() =
        if (TigerDuckTheme.isDarkMode) Color(0xFF30D158) else Color(0xFF34C759)
    val freeText: Color @Composable @ReadOnlyComposable get() =
        if (TigerDuckTheme.isDarkMode) Color(0xFF30D158) else Color(0xFF1E7B34)
    val bookedFill: Color @Composable @ReadOnlyComposable get() =
        if (TigerDuckTheme.isDarkMode) Color(0xFFFF9F0A) else Color(0xFFFF9500)
    val bookedText: Color @Composable @ReadOnlyComposable get() =
        if (TigerDuckTheme.isDarkMode) Color(0xFFFF9F0A) else Color(0xFFB25000)
}

/** The UI's locale, the one the app's strings resolve against. */
@Composable
@ReadOnlyComposable
internal fun finderLocale(): Locale = LocalLocale.current.platformLocale

/**
 * The locale's short date with its weekday: one letter long where the locale's one-letter
 * weekdays are all different, as Chinese ones are, else abbreviated.
 */
internal fun dateText(day: LocalDate, locale: Locale): String {
    val narrow = DayOfWeek.entries.map { it.getDisplayName(java.time.format.TextStyle.NARROW, locale) }
    val skeleton = if (narrow.toSet().size == narrow.size) "MdEEEEE" else "MdEEE"
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(day)
}

/** "Today Wed, 10/14", "Tomorrow Thu, 10/15", otherwise the date alone. */
@Composable
internal fun dayLabel(day: LocalDate, today: LocalDate): String {
    val date = dateText(day, finderLocale())
    return when (day) {
        today -> "${stringResource(R.string.calendar_today)} $date"
        today.plusDays(1) -> "${stringResource(R.string.widget_tomorrow)} $date"
        else -> date
    }
}

/**
 * The app-translation string naming the building [code], or null for a code it has none for.
 * One per building on the campus map (RoomPlace); Chinese names in Chinese, the official English
 * name, or the code and "Building", everywhere else (#610).
 */
@StringRes
internal fun buildingNameRes(code: String): Int? = when (code) {
    "AD" -> R.string.empty_classroom_building_ad
    "AU" -> R.string.empty_classroom_building_au
    "E1" -> R.string.empty_classroom_building_e1
    "E2" -> R.string.empty_classroom_building_e2
    "EE" -> R.string.empty_classroom_building_ee
    "IA" -> R.string.empty_classroom_building_ia
    "IB" -> R.string.empty_classroom_building_ib
    "LB" -> R.string.empty_classroom_building_lb
    "MA" -> R.string.empty_classroom_building_ma
    "RB" -> R.string.empty_classroom_building_rb
    "T1" -> R.string.empty_classroom_building_t1
    "T2" -> R.string.empty_classroom_building_t2
    "T3" -> R.string.empty_classroom_building_t3
    "T4" -> R.string.empty_classroom_building_t4
    "TR" -> R.string.empty_classroom_building_tr
    else -> null
}

/**
 * A building's name in the user's language ([buildingNameRes]); for a code without one, the name
 * the site's building list gives it (研揚大樓, 第四教學大樓 …), or else the code.
 */
@Composable
internal fun buildingName(code: String, siteNames: Map<String, String>): String =
    buildingNameRes(code)?.let { stringResource(it) }
        ?: siteNames[code]?.trim()?.takeIf { it.isNotEmpty() }
        ?: code

/** What a TalkBack label's parts are joined with: the full-width comma in Chinese, ", " otherwise. */
internal fun labelSeparator(languageTag: String): String =
    if (AppLanguageManager.isChineseLanguageTag(languageTag)) "，" else ", "

/** A TalkBack label from its non-empty [parts], as the UI language joins a list. */
@Composable
@ReadOnlyComposable
internal fun joinLabel(vararg parts: String): String =
    parts.filter { it.isNotEmpty() }.joinToString(labelSeparator(finderLocale().toLanguageTag()))

/** "Free 10:20 to 12:10", for TalkBack. */
@Composable
internal fun freeSpanLabel(first: Int, last: Int): String = stringResource(
    R.string.a11y_empty_classroom_free_span,
    EmptyClassroomSchedule.startTime(first),
    EmptyClassroomSchedule.endTime(last),
)

/**
 * The dense rows (a room's row, the day sheet's periods, the route line) stop growing at the
 * first size past the largest standard one: beyond it a name, a bar and a time no longer fit one
 * phone-width row.
 */
internal const val DENSE_ROW_MAX_FONT_SCALE = 1.5f

@Composable
internal fun CappedFontScale(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val capped = density.fontScale.coerceAtMost(DENSE_ROW_MAX_FONT_SCALE)
    if (capped == density.fontScale) {
        content()
    } else {
        CompositionLocalProvider(LocalDensity provides Density(density.density, capped), content = content)
    }
}

/**
 * A room name in a column as wide as the usual form, such as TR-214; a longer name shrinks into
 * it, down to 60% of the size.
 */
@Composable
internal fun RoomName(room: String, style: TextStyle, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val column = remember(style, measurer) { measurer.measure("TR-000", style).size.width }
    val width = remember(room, style, measurer) { measurer.measure(room, style).size.width }
    val scale = if (width > column && width > 0) (column.toFloat() / width).coerceAtLeast(0.6f) else 1f
    val columnDp = with(LocalDensity.current) { column.toDp() }
    Text(
        text = room,
        style = if (scale < 1f) style.copy(fontSize = style.fontSize * scale) else style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.width(columnDp),
    )
}

/** One line of [text] at [style]'s size, shrinking toward [minScale] of it where the line is too narrow. */
@Composable
internal fun ShrinkingText(text: String, style: TextStyle, minScale: Float, modifier: Modifier = Modifier) {
    val color = style.color.takeOrElse { LocalContentColor.current }
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(
            minFontSize = style.fontSize * minScale,
            maxFontSize = style.fontSize,
            stepSize = 1.sp,
        ),
    )
}
