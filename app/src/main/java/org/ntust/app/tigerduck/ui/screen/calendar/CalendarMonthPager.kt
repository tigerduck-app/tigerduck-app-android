package org.ntust.app.tigerduck.ui.screen.calendar

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * The month grid's pages: one a month, January 1900 through December 2099, as the iPhone's
 * (tigerduck-app#1033). A pager needs a count, and this one is far beyond anything the school's
 * data covers.
 */
internal object MonthPages {
    private const val FIRST_YEAR = 1900
    const val COUNT = 200 * 12

    /** The page of the month [date] falls in, in Taipei; the first or the last outside the range. */
    fun pageOf(date: Date): Int {
        val day = date.taipeiDate()
        return ((day.year - FIRST_YEAR) * 12 + day.monthValue - 1).coerceIn(0, COUNT - 1)
    }

    /** The first moment, in Taipei, of the month on [page]. */
    fun monthAt(page: Int): Date = Date.from(
        YearMonth.of(FIRST_YEAR + page / 12, page % 12 + 1).atDay(1).atStartOfDay(AppConstants.TAIPEI_ZONE).toInstant(),
    )
}

/** What TalkBack says of [month]: its name and year in [locale]'s own form ("December 2026", "2026年12月"). */
internal fun monthAnnouncement(month: Date, locale: Locale): String =
    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "yMMMM"), locale).format(month.taipeiDate())

/** How long a month waits to be said after the grid rests, so a turn started meanwhile can call it off. */
internal const val MONTH_ANNOUNCEMENT_DELAY_MS = 100L

/** The month grid's pager, for the tests. */
internal const val MONTH_PAGER_TAG = "calendar_month_pager"

/**
 * The turns of the month grid that the arrows and Today make, and what TalkBack hears of the
 * others, as the iPhone's: the month is said once a swipe, or TalkBack's own page scroll, leaves
 * the grid on another month; the arrows and Today say nothing, and call off a month still to be
 * said.
 */
private class MonthTurns(
    private val pager: PagerState,
    private val scope: CoroutineScope,
) {
    /** The page an arrow or Today is turning the grid to: the rest there is theirs, and says nothing. */
    private var turningTo: Int? = null
    private var announcement: Job? = null

    /** The month the grid last told the view model of, which comes back as the view model's month. */
    var told: Date? = null

    /**
     * The view model's month is [month]: Today, which calls off a month still to be said even when
     * it is the month shown, as any button does; or the grid's own rest coming back, which does not.
     */
    fun follow(month: Date) {
        if (month != told) turn(MonthPages.pageOf(month))
    }

    /**
     * Turns the grid to [target]: one month over slides, as a swipe does, and a farther one jumps,
     * since the months between are not worth passing. The page a turn under way is headed for is
     * where the next month counts from, so a second tap goes on from it.
     */
    fun turn(target: Int) {
        // A button pressed calls off a month still to be said, even when it turns nothing.
        announcement?.cancel()
        if (target !in 0 until MonthPages.COUNT) return
        if (target == pager.targetPage) {
            // A swipe already headed there: its rest is the button's too, and says nothing.
            if (pager.isScrollInProgress) turningTo = target
            return
        }
        turningTo = target
        val nextDoor = abs(target - pager.targetPage) == 1
        scope.launch {
            try {
                if (nextDoor) pager.animateScrollToPage(target) else pager.scrollToPage(target)
            } catch (e: CancellationException) {
                // A drag or TalkBack's page scroll took the grid over, and where it rests is
                // theirs; another turn has a page of its own.
                if (turningTo == target) turningTo = null
                throw e
            }
        }
    }

    /**
     * The grid came to rest on [page], another month when [moved]: [say] the month unless an arrow
     * or Today brought it there. Each rest ends the turn there was, so one a drag took back to the
     * month it left does not keep its page to silence a later swipe there.
     */
    fun rested(page: Int, moved: Boolean, month: () -> String, say: (String) -> Unit) {
        val byButton = page == turningTo
        turningTo = null
        if (!moved || byButton) return
        announcement?.cancel()
        announcement = scope.launch {
            delay(MONTH_ANNOUNCEMENT_DELAY_MS)
            say(month())
        }
    }
}

/**
 * The month grid with its arrows and weekday row. Only the grid pages: the title and the weekday
 * row stay put, as do the day list below. The title follows the month under the grid's middle; the
 * view model hears of a new month once the grid rests on it. The grid is as tall as the tallest
 * month on screen, so it never changes height in the middle of a swipe. [announce] says a month
 * through TalkBack; a test hears it instead.
 */
@Composable
internal fun MonthCalendar(
    displayedMonth: Date,
    selectedDate: Date,
    eventsByDay: Map<LocalDate, List<CalendarEvent>>,
    onDateSelected: (Date) -> Unit,
    onMonthChanged: (Date) -> Unit,
    announce: (String) -> Unit = rememberAnnouncer(),
) {
    // On its month from the first frame: opened anywhere else, it passed the months between.
    val pagerState = rememberPagerState(initialPage = MonthPages.pageOf(displayedMonth)) { MonthPages.COUNT }
    val scope = rememberCoroutineScope()
    val turns = remember(pagerState, scope) { MonthTurns(pagerState, scope) }
    val locale = LocalConfiguration.current.locales[0]

    // The view model's month to the grid: Today, or a swipe's own rest coming back.
    LaunchedEffect(displayedMonth) { turns.follow(displayedMonth) }

    // The grid's rest to the view model, once it rests: the page under the middle mid-swipe
    // would hand the effect above a month to turn back to. Null while the grid moves, so a rest
    // back on the month it left counts too.
    val currentDisplayedMonth by rememberUpdatedState(displayedMonth)
    val currentOnMonthChanged by rememberUpdatedState(onMonthChanged)
    val currentAnnounce by rememberUpdatedState(announce)
    val currentLocale by rememberUpdatedState(locale)
    LaunchedEffect(pagerState) {
        var last = pagerState.settledPage
        snapshotFlow { if (pagerState.isScrollInProgress) null else pagerState.settledPage }.filterNotNull().collect { settled ->
            val moved = settled != last
            last = settled
            val month = MonthPages.monthAt(settled)
            // Any rest away from the view model's month, one back where the grid was included:
            // the view model may have moved meanwhile, as Today does that an arrow turns back.
            if (MonthPages.pageOf(currentDisplayedMonth) != settled) {
                turns.told = month
                currentOnMonthChanged(month)
            }
            turns.rested(settled, moved, month = { monthAnnouncement(month, currentLocale) }, say = { currentAnnounce(it) })
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        MonthNav(
            month = MonthPages.monthAt(pagerState.currentPage).taipeiDate(),
            onPrevious = { turns.turn(pagerState.targetPage - 1) },
            onNext = { turns.turn(pagerState.targetPage + 1) },
        )
        WeekdayRow()
        Spacer(Modifier.height(4.dp))
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().testTag(MONTH_PAGER_TAG),
            verticalAlignment = Alignment.Top,
        ) { page ->
            CalendarGrid(
                monthDate = MonthPages.monthAt(page),
                selectedDate = selectedDate,
                eventsByDay = eventsByDay,
                onDateSelected = onDateSelected,
            )
        }
    }
}

@Composable
private fun MonthNav(month: LocalDate, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.Filled.ChevronLeft, stringResource(R.string.calendar_previous_month))
        }
        Text(
            text = stringResource(R.string.calendar_month_year, month.year, month.monthValue),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center
        )
        IconButton(onClick = onNext) {
            Icon(Icons.Filled.ChevronRight, stringResource(R.string.calendar_next_month))
        }
    }
}

@Composable
private fun WeekdayRow() {
    Row {
        listOf(
            stringResource(R.string.weekday_mon_short),
            stringResource(R.string.weekday_tue_short),
            stringResource(R.string.weekday_wed_short),
            stringResource(R.string.weekday_thu_short),
            stringResource(R.string.weekday_fri_short),
            stringResource(R.string.weekday_sat_short),
            stringResource(R.string.weekday_sun_short)
        ).forEach {
            Text(
                text = it,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
            )
        }
    }
}

/** Says a text through TalkBack, when it runs. */
@Composable
private fun rememberAnnouncer(): (String) -> Unit {
    val view = LocalView.current
    return remember(view) {
        { text ->
            // Compose has no announcement of its own, and a live region on the title would also
            // speak after the arrows, which the iPhone keeps quiet.
            @Suppress("DEPRECATION")
            view.announceForAccessibility(text)
        }
    }
}
