package org.ntust.app.tigerduck.ui.screen.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.CalendarEvent
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
    val context = LocalContext.current

    var showCheckmark by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(Unit) {
        viewModel.syncCompleteEvent.collect {
            showCheckmark = true
            delay(2000)
            showCheckmark = false
        }
    }
    LaunchedEffect(Unit) {
        viewModel.noNetworkEvent.collect {
            snackbarHostState.showSnackbar(context.getString(R.string.error_network_unavailable))
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

            item {
                MonthCalendar(
                    displayedMonth = displayedMonth,
                    selectedDate = selectedDate,
                    events = events,
                    onDateSelected = { viewModel.selectDate(it) },
                    onPreviousMonth = { viewModel.previousMonth() },
                    onNextMonth = { viewModel.nextMonth() }
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
                            if (isLoggedIn) stringResource(R.string.calendar_no_events_on_day)
                            else stringResource(R.string.common_login_required_feature),
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
    SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    } // Box
}

@Composable
private fun MonthCalendar(
    displayedMonth: Date,
    selectedDate: Date,
    events: List<CalendarEvent>,
    onDateSelected: (Date) -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit
) {
    val taipeiTz = org.ntust.app.tigerduck.AppConstants.TAIPEI_TZ
    val cal = Calendar.getInstance(taipeiTz).apply { time = displayedMonth }
    val year = cal.get(Calendar.YEAR)
    val month = cal.get(Calendar.MONTH)
    val monthLabel = stringResource(R.string.calendar_month_year, year, month + 1)

    // Build days
    val firstDay = Calendar.getInstance(taipeiTz).apply { set(year, month, 1) }
    val daysInMonth = firstDay.getActualMaximum(Calendar.DAY_OF_MONTH)
    // Sunday = 1, Monday = 2... adjust to Mon-first
    val startDow = ((firstDay.get(Calendar.DAY_OF_WEEK) - 2 + 7) % 7)

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        // Month nav
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPreviousMonth) {
                Icon(Icons.Filled.ChevronLeft, stringResource(R.string.calendar_previous_month))
            }
            Text(
                text = monthLabel,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            IconButton(onClick = onNextMonth) {
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

        // Calendar grid
        val totalCells = startDow + daysInMonth
        val rows = (totalCells + 6) / 7

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
    }
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
