package org.ntust.app.tigerduck.ui.screen.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.ntust.app.tigerduck.ui.component.EmptyStateView
import org.ntust.app.tigerduck.ui.component.JumpToNowChip
import org.ntust.app.tigerduck.ui.component.PageHeader
import org.ntust.app.tigerduck.ui.component.SyncIndicator
import org.ntust.app.tigerduck.ui.component.TigerPullToRefresh
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import java.util.Calendar
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel = hiltViewModel()
) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
    val displayedMonth by viewModel.displayedMonth.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    val dayEvents by viewModel.selectedDateEvents.collectAsStateWithLifecycle()
    val resources = LocalResources.current

    var showCheckmark by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) { viewModel.load() }
    LaunchedEffect(viewModel) {
        viewModel.syncCompleteEvent.collect {
            showCheckmark = true
            delay(2000)
            showCheckmark = false
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.noNetworkEvent.collect {
            snackbarHostState.showSnackbar(resources.getString(R.string.error_network_unavailable))
        }
    }

    var pullProgress by remember { mutableFloatStateOf(0f) }

    Box(modifier = Modifier.fillMaxSize()) {
    TigerPullToRefresh(
        isRefreshing = isLoading,
        onRefresh = { viewModel.refresh() },
        onDragProgress = { pullProgress = it },
        modifier = Modifier.fillMaxSize(),
        refreshingMessage = stringResource(R.string.refreshing_message),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                PageHeader(title = stringResource(R.string.feature_calendar)) {
                    SyncIndicator(
                        isLoading = isLoading,
                        showCheckmark = showCheckmark,
                        dragProgress = pullProgress,
                    )
                    Spacer(Modifier.width(8.dp))
                    JumpToNowChip(label = stringResource(R.string.calendar_today), onClick = { viewModel.goToToday() })
                }
            }

            if (!isLoggedIn) {
                item {
                    EmptyStateView(
                        icon = Icons.Filled.Lock,
                        title = stringResource(R.string.common_not_logged_in),
                        message = stringResource(R.string.common_login_required_feature),
                    )
                }
            } else {
                item {
                    MonthCalendar(
                        displayedMonth = displayedMonth,
                        selectedDate = selectedDate,
                        events = events,
                        onDateSelected = { viewModel.selectDate(it) },
                        onMonthChanged = { viewModel.setDisplayedMonth(it) }
                    )
                }

                item {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }

                if (dayEvents.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
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
                        EventRow(event)
                    }
                }
            }
        }

    }
    SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    } // Box
}

@Composable
private fun MonthCalendar(
    displayedMonth: Date,
    selectedDate: Date,
    events: List<CalendarEvent>,
    onDateSelected: (Date) -> Unit,
    onMonthChanged: (Date) -> Unit
) {
    val taipeiTz = org.ntust.app.tigerduck.AppConstants.TAIPEI_TZ
    val coroutineScope = rememberCoroutineScope()

    val baseDate = remember {
        Calendar.getInstance(taipeiTz).apply {
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

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
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
                events = events,
                onDateSelected = onDateSelected
            )
        }
    }
}

@Composable
private fun CalendarGrid(
    monthDate: Date,
    selectedDate: Date,
    events: List<CalendarEvent>,
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

    Column(modifier = Modifier.fillMaxWidth()) {
        repeat(rows) { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                repeat(7) { col ->
                    val cellIndex = row * 7 + col
                    val dayNum = cellIndex - startDow + 1

                    if (dayNum < 1 || dayNum > daysInMonth) {
                        Box(modifier = Modifier.weight(1f).height(40.dp))
                    } else {
                        val dayDate = Calendar.getInstance(taipeiTz).apply {
                            set(year, month, dayNum, 0, 0, 0)
                            set(Calendar.MILLISECOND, 0)
                        }.time

                        val isSelected = isSameDay(dayDate, selectedDate)
                        val isToday = isSameDay(dayDate, Date())
                        val hasEvent = events.any { isSameDay(it.date, dayDate) }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isSelected -> MaterialTheme.colorScheme.primary
                                        isToday -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                        else -> Color.Transparent
                                    }
                                )
                                .clickable { onDateSelected(dayDate) },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "$dayNum",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = when {
                                        isSelected -> MaterialTheme.colorScheme.onPrimary
                                        isToday -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                    fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                if (hasEvent) {
                                    Box(
                                        modifier = Modifier
                                            .size(4.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isSelected) Color.White
                                                else MaterialTheme.colorScheme.primary
                                            )
                                    )
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
    val calStart = Calendar.getInstance(org.ntust.app.tigerduck.AppConstants.TAIPEI_TZ).apply { time = start }
    val calEnd = Calendar.getInstance(org.ntust.app.tigerduck.AppConstants.TAIPEI_TZ).apply { time = end }
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
private fun EventRow(event: CalendarEvent) {
    val timeFmt = remember {
        SimpleDateFormat("HH:mm", Locale.TAIWAN).apply {
            timeZone = org.ntust.app.tigerduck.AppConstants.TAIPEI_TZ
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
        Text(
            timeFmt.format(event.date),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY)
        )
    }
}

private fun isSameDay(a: Date, b: Date): Boolean {
    val ca = Calendar.getInstance(org.ntust.app.tigerduck.AppConstants.TAIPEI_TZ).apply { time = a }
    val cb = Calendar.getInstance(org.ntust.app.tigerduck.AppConstants.TAIPEI_TZ).apply { time = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
           ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}
