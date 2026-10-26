package org.ntust.app.tigerduck.ui.screen.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.material3.Switch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.ui.component.EmptyStateView
import org.ntust.app.tigerduck.ui.component.JumpToNowChip
import org.ntust.app.tigerduck.ui.component.PageHeader
import org.ntust.app.tigerduck.ui.component.ServerKind
import org.ntust.app.tigerduck.ui.component.SyncStatusDot
import org.ntust.app.tigerduck.ui.component.TigerPullToRefresh
import org.ntust.app.tigerduck.ui.component.scrollbar
import org.ntust.app.tigerduck.ui.component.PageLeftEffect
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel = hiltViewModel(),
    onOpenSignInSettings: () -> Unit = {},
    onOpenAssignment: (assignmentId: String) -> Unit = {},
) {
    val eventsByDay by viewModel.eventsByDay.collectAsStateWithLifecycle()
    val holidayOverrides by viewModel.holidayOverrides.collectAsStateWithLifecycle()
    val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
    val displayedMonth by viewModel.displayedMonth.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val schoolDataSyncedAtMs by viewModel.schoolDataSyncedAtMs.collectAsStateWithLifecycle()
    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    val dayEvents by viewModel.selectedDateEvents.collectAsStateWithLifecycle()
    val resources = LocalResources.current

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) { 
        viewModel.load()
        // Each time the page enters composition: a return to it from
        // another page fetches if its RefreshPolicy asks for that.
        viewModel.onPageShown()
    }
    PageLeftEffect(viewModel::onPageLeft)
    LaunchedEffect(viewModel) {
        viewModel.noNetworkEvent.collect {
            snackbarHostState.showSnackbar(resources.getString(R.string.error_network_unavailable))
        }
    }


    Box(modifier = Modifier.fillMaxSize()) {
        TigerPullToRefresh(
            isRefreshing = isLoading,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize(),
            refreshingMessage = stringResource(R.string.refreshing_message),
        ) {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().scrollbar(listState),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                item {
                    PageHeader(title = stringResource(R.string.feature_calendar)) {
                        SyncStatusDot(
                            // The TigerDuck backend publishes the semester
                            // boundaries and holidays this screen now shows,
                            // so it belongs in the source list beside Moodle.
                            servers = listOf(ServerKind.MOODLE, ServerKind.BACKEND),
                            isLoading = isLoading,
                            syncedAtMs = schoolDataSyncedAtMs,
                        )
                        Spacer(Modifier.width(8.dp))
                        JumpToNowChip(
                            label = stringResource(R.string.calendar_today),
                            onClick = { viewModel.goToToday() })
                    }
                }

                if (!isLoggedIn) {
                    item {
                        EmptyStateView(
                            icon = Icons.Filled.Lock,
                            title = stringResource(R.string.common_not_signed_in),
                            message = stringResource(R.string.common_sign_in_required_feature),
                            actionLabel = stringResource(R.string.onboarding_sign_in_button),
                            onAction = onOpenSignInSettings,
                        )
                    }
                } else {
                    item {
                        MonthCalendar(
                            displayedMonth = displayedMonth,
                            selectedDate = selectedDate,
                            eventsByDay = eventsByDay,
                            onDateSelected = { viewModel.selectDate(it) },
                            onMonthChanged = { viewModel.setDisplayedMonth(it) }
                        )
                    }

                    item {
                        HorizontalDivider(
                            modifier = Modifier.padding(
                                horizontal = 16.dp,
                                vertical = 8.dp
                            )
                        )
                    }

                    item {
                        CalendarDayHeading(
                            date = selectedDate.taipeiDate(),
                            today = Date(AppClock.nowMillis()).taipeiDate(),
                        )
                    }

                    if (dayEvents.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    stringResource(R.string.calendar_no_events_on_day),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
                                )
                            }
                        }
                    } else {
                        items(dayEvents) { event ->
                            val holidayId = viewModel.holidayIdFor(event)
                            EventRow(
                                event = event,
                                // Only a holiday can be opted back into; a
                                // term boundary is an announcement, not a
                                // day off, so it gets no toggle.
                                notifyOnHoliday = holidayId?.let { it in holidayOverrides },
                                onNotifyOnHolidayChange = holidayId?.let { id ->
                                    { on: Boolean -> viewModel.setNotifyOnHoliday(id, on) }
                                },
                                onOpenAssignment = onOpenAssignment,
                            )
                        }
                    }
                }
            }

        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    } // Box
}

/**
 * The selected date over its events, as the iPhone heads the list: after a
 * swipe to another month the grid alone no longer says which day this is.
 */
@Composable
internal fun CalendarDayHeading(date: LocalDate, today: LocalDate) {
    SectionHeader(
        dayTitle(
            date = date,
            today = today,
            locale = LocalConfiguration.current.locales[0],
            todaySuffix = stringResource(R.string.calendar_date_today_suffix),
        ),
        // So TalkBack's heading navigation jumps from the grid to the list.
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun MonthCalendar(
    displayedMonth: Date,
    selectedDate: Date,
    eventsByDay: Map<LocalDate, List<CalendarEvent>>,
    onDateSelected: (Date) -> Unit,
    onMonthChanged: (Date) -> Unit
) {
    val taipeiTz = org.ntust.app.tigerduck.AppConstants.TAIPEI_TZ
    val coroutineScope = rememberCoroutineScope()

    val baseDate = remember {
        AppClock.calendar().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.time
    }

    val pagerState = rememberPagerState(initialPage = 1000) { 2000 }

    // Sync pager with ViewModel's displayedMonth (VM → pager).
    LaunchedEffect(displayedMonth) {
        val diff = getYearMonthDiff(baseDate, displayedMonth)
        val targetPage = 1000 + diff
        if (pagerState.currentPage != targetPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }

    // Sync ViewModel with pager once it SETTLES (pager → VM). Listening to
    // currentPage instead reports transient values during fast swipes /
    // animateScrollToPage and re-triggers the VM→pager effect, producing a
    // ping-pong. settledPage debounces this to the post-animation page only.
    val currentDisplayedMonth by rememberUpdatedState(displayedMonth)
    val currentOnMonthChanged by rememberUpdatedState(onMonthChanged)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { settled ->
                val diff = settled - 1000
                val newMonth = getDateFromDiff(baseDate, diff)
                if (!isSameMonth(newMonth, currentDisplayedMonth)) {
                    currentOnMonthChanged(newMonth)
                }
            }
    }

    val cal = Calendar.getInstance(taipeiTz).apply { time = displayedMonth }
    val year = cal.get(Calendar.YEAR)
    val month = cal.get(Calendar.MONTH)
    val monthLabel = stringResource(R.string.calendar_month_year, year, month + 1)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        // Month nav
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                coroutineScope.launch {
                    pagerState.animateScrollToPage(pagerState.currentPage - 1)
                }
            }) {
                Icon(Icons.Filled.ChevronLeft, stringResource(R.string.calendar_previous_month))
            }
            Text(
                text = monthLabel,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            IconButton(onClick = {
                coroutineScope.launch {
                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                }
            }) {
                Icon(Icons.Filled.ChevronRight, stringResource(R.string.calendar_next_month))
            }
        }

        // Day of week headers
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

        Spacer(Modifier.height(4.dp))

        // HorizontalPager for the grid
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) { page ->
            val monthDate = getDateFromDiff(baseDate, page - 1000)
            CalendarGrid(
                monthDate = monthDate,
                selectedDate = selectedDate,
                eventsByDay = eventsByDay,
                onDateSelected = onDateSelected
            )
        }
    }
}

@Composable
internal fun CalendarGrid(
    monthDate: Date,
    selectedDate: Date,
    eventsByDay: Map<LocalDate, List<CalendarEvent>>,
    onDateSelected: (Date) -> Unit
) {
    val taipeiTz = org.ntust.app.tigerduck.AppConstants.TAIPEI_TZ
    val cal = Calendar.getInstance(taipeiTz).apply { time = monthDate }
    val year = cal.get(Calendar.YEAR)
    val month = cal.get(Calendar.MONTH)

    val firstDay = Calendar.getInstance(taipeiTz).apply {
        set(year, month, 1, 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val daysInMonth = firstDay.getActualMaximum(Calendar.DAY_OF_MONTH)
    val startDow = ((firstDay.get(Calendar.DAY_OF_WEEK) - 2 + 7) % 7)

    val totalCells = startDow + daysInMonth
    val rows = (totalCells + 6) / 7
    val selectedDay = selectedDate.taipeiDate()
    val today = Date(AppClock.nowMillis()).taipeiDate()
    val locale = LocalConfiguration.current.locales[0]
    val dayDescription = stringResource(R.string.a11y_calendar_day)

    Column(modifier = Modifier.fillMaxWidth()) {
        repeat(rows) { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                repeat(7) { col ->
                    val cellIndex = row * 7 + col
                    val dayNum = cellIndex - startDow + 1

                    if (dayNum < 1 || dayNum > daysInMonth) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                        )
                    } else {
                        val dayDate = Calendar.getInstance(taipeiTz).apply {
                            set(year, month, dayNum, 0, 0, 0)
                            set(Calendar.MILLISECOND, 0)
                        }.time

                        val day = LocalDate.of(year, month + 1, dayNum)
                        val isSelected = day == selectedDay
                        val isToday = day == today
                        val dayEvents = eventsByDay[day].orEmpty()
                        val dotSources = remember(dayEvents) { dotSources(dayEvents) }
                        val description = remember(dayDescription, day, dayEvents.size, locale) {
                            dayCellDescription(dayDescription, day, dayEvents.size, locale)
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clip(CircleShape)
                                // The chosen day in the accent's own fill, the
                                // gradient picked if one is; today only tinted.
                                .then(
                                    if (isSelected) {
                                        Modifier.background(TigerDuckTheme.accentBrush)
                                    } else {
                                        Modifier.background(
                                            if (isToday) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
                                        )
                                    },
                                )
                                .clickable(role = Role.Button) { onDateSelected(dayDate) }
                                // The full date and the event count in place of the
                                // bare day number, as iOS reads it. Only the chosen
                                // day says it is selected: "not selected" on every
                                // other day would be noise. Cleared here, on the
                                // clickable node itself: a child that clears its own
                                // semantics leaves this node unclickable to TalkBack.
                                .clearAndSetSemantics {
                                    contentDescription = description
                                    if (isSelected) selected = true
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "$dayNum",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = when {
                                        isSelected -> TigerDuckTheme.onAccentBrush
                                        isToday -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                    fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                if (dotSources.isNotEmpty()) {
                                    // One dot per source in its color, as on
                                    // the iPhone, so a deadline reads apart
                                    // from a holiday. On the selected day they
                                    // take the day number's color: a source
                                    // color can sit too close to the accent
                                    // fill, and the list below shows that
                                    // day's sources anyway. Not white: the
                                    // wallpaper's primary in dark mode is
                                    // light enough to hide it.
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        dotSources.forEach { source ->
                                            Box(
                                                modifier = Modifier
                                                    .size(4.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (isSelected) TigerDuckTheme.onAccentBrush else source.color
                                                    )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // Add empty rows to keep height consistent if needed (e.g., always 6 rows)
        if (rows < 6) {
            repeat(6 - rows) {
                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }
}

private fun getYearMonthDiff(start: Date, end: Date): Int {
    val calStart =
        Calendar.getInstance(org.ntust.app.tigerduck.AppConstants.TAIPEI_TZ).apply { time = start }
    val calEnd =
        Calendar.getInstance(org.ntust.app.tigerduck.AppConstants.TAIPEI_TZ).apply { time = end }
    val yearDiff = calEnd.get(Calendar.YEAR) - calStart.get(Calendar.YEAR)
    val monthDiff = calEnd.get(Calendar.MONTH) - calStart.get(Calendar.MONTH)
    return yearDiff * 12 + monthDiff
}

private fun getDateFromDiff(baseDate: Date, monthDiff: Int): Date {
    return Calendar.getInstance(org.ntust.app.tigerduck.AppConstants.TAIPEI_TZ).apply {
        time = baseDate
        add(Calendar.MONTH, monthDiff)
    }.time
}

private fun isSameMonth(a: Date, b: Date): Boolean {
    val ca = Calendar.getInstance(org.ntust.app.tigerduck.AppConstants.TAIPEI_TZ).apply { time = a }
    val cb = Calendar.getInstance(org.ntust.app.tigerduck.AppConstants.TAIPEI_TZ).apply { time = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.MONTH) == cb.get(Calendar.MONTH)
}


@Composable
internal fun EventRow(
    event: CalendarEvent,
    notifyOnHoliday: Boolean? = null,
    onNotifyOnHolidayChange: ((Boolean) -> Unit)? = null,
    onOpenAssignment: ((assignmentId: String) -> Unit)? = null,
) {
    val timeFmt = remember {
        SimpleDateFormat("HH:mm", Locale.TAIWAN).apply {
            timeZone = org.ntust.app.tigerduck.AppConstants.TAIPEI_TZ
        }
    }
    // A Moodle deadline opens its assignment's page, as a row on Home does.
    val assignmentId = event.moodleAssignmentId
    val open = if (assignmentId != null && onOpenAssignment != null) {
        Modifier.clickable(role = Role.Button) { onOpenAssignment(assignmentId) }
    } else {
        Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(open)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(event.source.color)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(event.title, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(event.source.labelRes),
                style = MaterialTheme.typography.labelSmall,
                color = event.source.color
            )
        }
        if (notifyOnHoliday != null && onNotifyOnHolidayChange != null) {
            // Where a timed row has its time: a holiday is a whole day, so it
            // has none to show.
            //
            // The switch is labelled rather than bare: on its own it asked
            // the user to guess what it governed, and a screen reader had
            // only the accessibility string to go on.
            Text(
                stringResource(R.string.calendar_holiday_notify_title),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = notifyOnHoliday,
                onCheckedChange = onNotifyOnHolidayChange,
            )
        } else if (!event.isAllDay) {
            // A whole day has no time worth showing: "00:00" read as a
            // midnight deadline.
            Text(
                timeFmt.format(event.date),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
            )
        }
    }
}
