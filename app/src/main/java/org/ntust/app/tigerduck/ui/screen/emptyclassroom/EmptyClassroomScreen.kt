package org.ntust.app.tigerduck.ui.screen.emptyclassroom

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.activity.compose.LocalActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.emptyclassroom.BuildingDirectory
import org.ntust.app.tigerduck.emptyclassroom.EmptyClassroomSchedule
import org.ntust.app.tigerduck.emptyclassroom.RoomPlace
import org.ntust.app.tigerduck.emptyclassroom.RoomSuggestion
import org.ntust.app.tigerduck.emptyclassroom.RoomUsage
import org.ntust.app.tigerduck.ui.component.EmptyStateView
import org.ntust.app.tigerduck.ui.component.PageHeader
import org.ntust.app.tigerduck.ui.component.SyncStatusDot
import org.ntust.app.tigerduck.ui.component.TigerPullToRefresh
import org.ntust.app.tigerduck.ui.component.statusText

/**
 * More's empty-classroom finder: free rooms on the main campus for a day and period, with a room
 * to wait in between the user's classes.
 */
@Composable
fun EmptyClassroomScreen(
    onOpenSignInSettings: () -> Unit,
    viewModel: EmptyClassroomViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()

    val activity = LocalActivity.current
    // Back in the foreground the screen reads again, which sends nothing while the day is fresh
    // and joins a read still running. Only a real trip to the background counts: a dark-mode or
    // text-size change rebuilds the activity, which is no visit of its own.
    LifecycleStartEffect(viewModel) {
        viewModel.appear()
        onStopOrDispose { if (activity?.isChangingConfigurations != true) viewModel.disappear() }
    }

    Box(Modifier.fillMaxSize()) {
        TigerPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = { if (signedIn) viewModel.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                item {
                    PageHeader(title = stringResource(R.string.feature_empty_classroom)) {
                        // The room booking site is a source of its own, which none of the shared
                        // dot's servers stand for, so the page reports how it last answered.
                        if (signedIn) {
                            SyncStatusDot(
                                status = state.siteStatus,
                                label = stringResource(R.string.feature_empty_classroom),
                                icon = Icons.Filled.Business,
                                text = statusText(state.siteStatus),
                                isLoading = state.isRefreshing || state.isReloading ||
                                    state.content == FinderContent.Loading,
                                syncedAtMs = state.syncedAtMs,
                            )
                        }
                    }
                }
                if (!signedIn) {
                    item {
                        EmptyStateView(
                            icon = Icons.Filled.Lock,
                            title = stringResource(R.string.common_not_signed_in),
                            message = stringResource(R.string.common_sign_in_required_feature),
                            modifier = Modifier.padding(top = 32.dp),
                            onIconClick = onOpenSignInSettings,
                        )
                    }
                    item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            FilledTonalButton(onClick = onOpenSignInSettings) {
                                Text(stringResource(R.string.onboarding_sign_in_button))
                            }
                        }
                    }
                } else {
                    finderContent(state, viewModel, onRoom = viewModel::open)
                }
            }
        }
    }

    // Looked up in the rooms on screen, so a read that lands while the sheet is open reaches it;
    // another day, an account change, or rooms without it close the sheet.
    val room = state.openRoom
    if (room != null) {
        val siteNames = state.usage?.buildingNames.orEmpty()
        val place = RoomPlace.of(room.room)
        val placeText = place?.let { "${buildingName(it.building, siteNames)} ${it.floorLabel}" } ?: room.room
        RoomDaySheet(
            room = room,
            place = placeText,
            day = dateText(state.selectedDay, finderLocale()),
            period = state.selectedPeriod,
            ended = state.endedPeriods,
            nowPeriod = state.currentPeriod,
            onDismiss = viewModel::closeRoom,
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.finderContent(
    state: FinderState,
    viewModel: EmptyClassroomViewModel,
    onRoom: (RoomUsage) -> Unit,
) {
    item {
        ClassroomTimeBar(
            days = state.days,
            today = state.today,
            selectedDay = state.selectedDay,
            period = state.selectedPeriod,
            onSelect = viewModel::select,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    item {
        PeriodChipRow(
            selected = state.selectedPeriod,
            current = state.currentPeriod,
            ended = state.endedPeriods,
            onSelect = viewModel::select,
        )
    }
    item {
        Box(Modifier.fillMaxWidth().height(4.dp).padding(horizontal = 16.dp)) {
            androidx.compose.animation.AnimatedVisibility(
                visible = state.isReloading,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
    when (val content = state.content) {
        FinderContent.Loading -> item {
            ClassroomSkeleton(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp))
        }
        is FinderContent.Failed -> item {
            ClassroomStateCard(
                icon = if (content.offline) Icons.Filled.CloudOff else Icons.Filled.WarningAmber,
                title = stringResource(R.string.empty_classroom_load_failed),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp),
            ) {
                FilledTonalButton(onClick = viewModel::retry) { Text(stringResource(R.string.action_retry)) }
            }
        }
        FinderContent.NoData -> item {
            DayOffCard(stringResource(R.string.empty_classroom_no_data), state, viewModel)
        }
        is FinderContent.HolidayOff -> item {
            DayOffCard(content.holiday.name(finderLocale().toLanguageTag()), state, viewModel)
        }
        is FinderContent.Rooms -> rooms(state, content, viewModel, onRoom)
    }
}

@Composable
private fun DayOffCard(title: String, state: FinderState, viewModel: EmptyClassroomViewModel) {
    ClassroomStateCard(
        icon = Icons.Filled.EventBusy,
        title = title,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp),
    ) {
        state.nextTeachingDay?.let { NextTeachingDayButton(it, viewModel::select) }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.rooms(
    state: FinderState,
    content: FinderContent.Rooms,
    viewModel: EmptyClassroomViewModel,
    onRoom: (RoomUsage) -> Unit,
) {
    val period = state.selectedPeriod
    val suggestion = RoomSuggestion.make(content.usage.rooms, period, state.classes)
    val directory = BuildingDirectory.of(content.usage.rooms, period)
    if (suggestion != null) {
        item(key = "suggestion") {
            SuggestionSection(
                suggestion = suggestion,
                ended = state.endedPeriods,
                onRoom = onRoom,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp),
            )
        }
    }
    val shown = directory.defaultBuilding(state.pickedBuilding, suggestion?.candidates?.firstOrNull()?.place?.building)
        ?: return
    item(key = "buildings") {
        Box(Modifier.padding(top = 28.dp)) {
            BuildingSection(
                directory = directory,
                shown = shown,
                siteNames = content.usage.buildingNames,
                period = period,
                ended = state.endedPeriods,
                onPick = viewModel::pick,
                onNextPeriod = if (period + 1 < EmptyClassroomSchedule.periodIds.size) {
                    { viewModel.select(period + 1) }
                } else {
                    null
                },
                onRoom = onRoom,
            )
        }
    }
}
