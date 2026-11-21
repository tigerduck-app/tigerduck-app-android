package org.ntust.app.tigerduck.ui.screen.classtable.sharing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.sharing.ImportedPerson
import org.ntust.app.tigerduck.data.sharing.ImportedPerson.Placement
import org.ntust.app.tigerduck.ui.component.PageScaffold
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.component.TigerDuckDialog
import org.ntust.app.tigerduck.ui.screen.classtable.sharing.ClassTableListRows.Header
import org.ntust.app.tigerduck.ui.screen.classtable.sharing.ClassTableListRows.Row
import org.ntust.app.tigerduck.ui.screen.classtable.sharing.ClassTableListRows.User

/** The test tag of a person's drag handle on the Class tables list. */
internal fun classTableListHandleTag(id: String) = "class-table-list-handle-$id"

/**
 * The Class tables list, opened from the class table's settings: the imported people under the
 * double tap's rotation, the long-press menu and Other. A tap renames, a swipe to the start deletes
 * and a handle drags a person within and between sections; the user's own class table stays first.
 * Ported from iOS `ClassTableListView`.
 */
@Composable
fun ClassTableListScreen(userName: String, onBack: () -> Unit, viewModel: ClassTableListViewModel = hiltViewModel()) {
    val tables by viewModel.tables.collectAsStateWithLifecycle()
    val saveFailed by viewModel.saveFailed.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<ImportedPerson?>(null) }
    var deleting by remember { mutableStateOf<ImportedPerson?>(null) }
    PageScaffold(title = stringResource(R.string.class_table_list_title), onBack = onBack) { padding ->
        ClassTableList(
            people = tables.people,
            userName = userName,
            actions = ClassTableListActions(onRename = { renaming = it }, onDelete = { deleting = it }, onMove = viewModel::move),
            resetKey = saveFailed,
            modifier = Modifier.padding(padding),
        )
    }
    renaming?.let { person ->
        ImportedPersonRenameDialog(person, { viewModel.rename(person.id, it); renaming = null }) { renaming = null }
    }
    deleting?.let { person ->
        ImportedPersonDeleteDialog(person, { viewModel.delete(person.id); deleting = null }) { deleting = null }
    }
    if (saveFailed) {
        TigerDuckDialog(
            onDismissRequest = viewModel::dismissSaveFailed,
            title = stringResource(R.string.bulletin_save_failed_title),
            confirmText = stringResource(R.string.action_confirm),
        )
    }
}

/** What a person's row does: rename, delete, and move into a placement before someone there or last. */
internal class ClassTableListActions(
    val onRename: (ImportedPerson) -> Unit,
    val onDelete: (ImportedPerson) -> Unit,
    val onMove: (id: String, placement: Placement, before: String?) -> Unit,
)

/**
 * The list's rows, which a drag reorders on screen and saves as it ends. They follow [people]
 * again once no drag is under way, and when [resetKey] changes, as after a move not saved.
 */
@Composable
internal fun ClassTableList(
    people: List<ImportedPerson>,
    userName: String,
    actions: ClassTableListActions,
    resetKey: Any? = null,
    modifier: Modifier = Modifier,
) {
    var rows by remember { mutableStateOf(ClassTableListRows.of(people)) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableMapOf<String, Int>() }
    val latestPeople by rememberUpdatedState(people)
    val latestActions by rememberUpdatedState(actions)
    LaunchedEffect(people, resetKey) { if (dragging == null) rows = ClassTableListRows.of(people) }
    val drag = remember {
        object : RowDrag {
            override fun start(key: String) {
                dragging = key
                offset = 0f
            }

            override fun by(delta: Float) {
                offset += delta
                while (true) {
                    val index = rows.indexOfFirst { it.key == dragging }.takeIf { it >= 0 } ?: break
                    val down = offset > 0
                    val past = rows.getOrNull(if (down) index + 1 else index - 1) ?: break
                    val height = heights[past.key]?.toFloat() ?: break
                    if (kotlin.math.abs(offset) < height / 2) break
                    rows = ClassTableListRows.step(rows, index, down) ?: break
                    offset += if (down) -height else height
                }
            }

            override fun end() {
                val index = rows.indexOfFirst { it.key == dragging }
                dragging = null
                offset = 0f
                val moved = rows.getOrNull(index) as? ClassTableListRows.Person ?: return
                if (rows != ClassTableListRows.of(latestPeople)) {
                    val (placement, before) = ClassTableListRows.target(rows, index)
                    latestActions.onMove(moved.person.id, placement, before)
                }
            }
        }
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
        rows.forEach { row ->
            key(row.key) {
                val lifted = row.key == dragging
                Box(
                    Modifier
                        .zIndex(if (lifted) 1f else 0f)
                        .graphicsLayer { translationY = if (lifted) offset else 0f }
                        .then(if (lifted) Modifier.shadow(4.dp) else Modifier)
                        .onSizeChanged { heights[row.key] = it.height },
                ) {
                    ClassTableListRow(row, userName, actions, drag)
                }
            }
        }
    }
}

/** A drag of one person's row by its handle. */
private interface RowDrag {
    fun start(key: String)
    fun by(delta: Float)
    fun end()
}

@Composable
private fun ClassTableListRow(row: Row, userName: String, actions: ClassTableListActions, drag: RowDrag) {
    when (row) {
        is Header -> SectionHeader(stringResource(row.placement.title), Modifier.padding(top = 12.dp, bottom = 4.dp))
        // The user's own class table: no handle, no swipe and no tap.
        User -> ListRowText(userName, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface))
        is ClassTableListRows.Person -> PersonRow(row.person, actions, drag)
    }
}

@Composable
private fun PersonRow(person: ImportedPerson, actions: ClassTableListActions, drag: RowDrag) {
    val dismiss = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val moves = Placement.entries.filter { it != person.placement }.map { placement ->
        val title = stringResource(placement.title)
        CustomAccessibilityAction(title) { actions.onMove(person.id, placement, null); true }
    }
    val deleteLabel = stringResource(R.string.class_table_delete_user)
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error).padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onError) }
        },
        modifier = Modifier.semantics {
            customActions = moves + CustomAccessibilityAction(deleteLabel) { actions.onDelete(person); true }
        },
        // The swipe asks first, as the class table's delete does, so the row comes back.
        onDismiss = {
            actions.onDelete(person)
            scope.launch { dismiss.reset() }
        },
    ) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).clickable { actions.onRename(person) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ListRowText(person.nickname, Modifier.weight(1f))
            DragHandle(person.id, drag)
        }
    }
}

@Composable
private fun DragHandle(id: String, drag: RowDrag) {
    Box(
        Modifier
            .size(48.dp)
            .testTag(classTableListHandleTag(id))
            // Up and down only, so a swipe to delete that starts on the handle still swipes.
            .pointerInput(id) {
                detectVerticalDragGestures(
                    onDragStart = { drag.start(id) },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        drag.by(amount)
                    },
                    onDragEnd = { drag.end() },
                    onDragCancel = { drag.end() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.DragHandle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ListRowText(text: String, modifier: Modifier = Modifier) {
    Box(modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
        Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The section's title, which the switcher's Other submenu shares. */
internal val Placement.title: Int
    get() = when (this) {
        Placement.ROTATION -> R.string.class_table_list_section_double_tap
        Placement.LONG_PRESS_MENU -> R.string.class_table_list_section_long_press
        Placement.OTHER -> R.string.class_table_list_section_other
    }
