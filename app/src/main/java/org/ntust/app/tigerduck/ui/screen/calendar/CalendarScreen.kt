package org.ntust.app.tigerduck.ui.screen.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.ui.component.EmptyStateView
import org.ntust.app.tigerduck.ui.component.PageHeaderPosition
import org.ntust.app.tigerduck.ui.component.PageScaffold
import org.ntust.app.tigerduck.ui.component.TigerPullToRefresh
import org.ntust.app.tigerduck.ui.component.scrollbar
import org.ntust.app.tigerduck.ui.component.PageLeftEffect
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme
import java.time.LocalDate
import java.util.Calendar
import java.util.Date

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
    val openable by viewModel.openableAssignmentIds.collectAsStateWithLifecycle()
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

    PageScaffold(
        title = stringResource(R.string.feature_calendar),
        headerPosition = PageHeaderPosition.InContent,
        syncDot = { CalendarSyncDot(isLoading, schoolDataSyncedAtMs) },
        actions = { CalendarTodayChip(onClick = { viewModel.goToToday() }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        TigerPullToRefresh(
            isRefreshing = isLoading,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(padding),
            refreshingMessage = stringResource(R.string.refreshing_message),
        ) {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().scrollbar(listState),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                item { Header() }

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
                                onOpen = event.assignmentToOpen(openable)?.let { id -> { onOpenAssignment(id) } },
                            )
                        }
                    }
                }
            }

        }
    }
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
    }
}
