package org.ntust.app.tigerduck.ui.screen.classtable.clubs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.ntust.app.tigerduck.clubs.ClubOccurrence

/** Which of the clubs' sheets is open over the class table. */
@Stable
internal class ClassTableClubSheets {
    /** 訂閱社團, from the class table's menu. */
    var subscribing by mutableStateOf(false)

    /** 選擇社團: a tapped block's activity and the others it meets. */
    var picker by mutableStateOf<List<ClubOccurrence>?>(null)

    var detail by mutableStateOf<ClubOccurrence?>(null)

    /** 編輯顏色, from a block's long press. */
    var recolor by mutableStateOf<ClubColorTarget?>(null)

    /** A tap on a block: the picker when it meets another club's activity, otherwise the activity's details. */
    fun tap(overlapping: List<ClubOccurrence>) {
        if (overlapping.size > 1) picker = overlapping else detail = overlapping.firstOrNull()
    }

    fun close() {
        subscribing = false
        picker = null
        detail = null
        recolor = null
    }
}

@Composable
internal fun rememberClassTableClubSheets(): ClassTableClubSheets = remember { ClassTableClubSheets() }

@Composable
internal fun ClassTableClubSheetsHost(sheets: ClassTableClubSheets, viewModel: ClassTableClubsViewModel) {
    if (sheets.subscribing) ClubSubscribeSheet(viewModel, onDismiss = { sheets.subscribing = false })
    sheets.picker?.let { clubs ->
        ClubPickerSheet(clubs, onPick = { sheets.picker = null; sheets.detail = it }, onDismiss = { sheets.picker = null })
    }
    sheets.detail?.let { club -> ClubActivityDetailSheet(club, onDismiss = { sheets.detail = null }) }
    sheets.recolor?.let { target ->
        ClubColorSheet(
            name = target.name,
            color = viewModel.color(target.id),
            onApply = { viewModel.setColor(target.id, it); sheets.recolor = null },
            onDismiss = { sheets.recolor = null },
        )
    }
}
