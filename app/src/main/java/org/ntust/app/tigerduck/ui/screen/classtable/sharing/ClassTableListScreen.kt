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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
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
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val drag = remember { ListDrag(people, actions, scroll, with(density) { LIST_PADDING.toPx() }, with(density) { 8.dp.toPx() }) }
    SideEffect {
        drag.people = people
        drag.actions = actions
    }
    LaunchedEffect(people, resetKey) { if (drag.dragging == null) drag.rows = ClassTableListRows.of(people) }
    DragEdgeScroll(drag)
    Column(
        modifier.fillMaxSize().onSizeChanged { drag.viewportHeight = it.height }.verticalScroll(scroll).padding(vertical = LIST_PADDING),
    ) {
        drag.rows.forEach { row ->
            key(row.key) {
                val lifted = row.key == drag.dragging
                Box(
                    Modifier
                        .zIndex(if (lifted) 1f else 0f)
                        .graphicsLayer { translationY = if (lifted) drag.offset else 0f }
                        .then(if (lifted) Modifier.shadow(4.dp) else Modifier)
                        .onSizeChanged { drag.heights[row.key] = it.height },
                ) {
                    ClassTableListRow(row, userName, actions, drag)
                }
            }
        }
    }
}

private val LIST_PADDING = 12.dp

/** A drag of one person's row by its handle. */
private interface RowDrag {
    fun start(key: String)
    fun by(delta: Float)
    fun end()
}

/**
 * The list's rows and the drag of one of them by [offset] from its place, which reorders them on
 * screen and saves the move as it ends. [people] and [actions] are kept the latest the list was
 * given; [padding] is the list's top padding and [slop] how far a finger goes before it heads
 * anywhere, in pixels.
 */
private class ListDrag(
    var people: List<ImportedPerson>,
    var actions: ClassTableListActions,
    private val scroll: ScrollState,
    private val padding: Float,
    private val slop: Float,
) : RowDrag {
    var rows by mutableStateOf(ClassTableListRows.of(people))
    var dragging by mutableStateOf<String?>(null)
    var offset by mutableFloatStateOf(0f)
    var viewportHeight by mutableIntStateOf(0)
    val heights = mutableMapOf<String, Int>()

    /** Which way the finger is heading, the edge scroll's cue. */
    private var heading = DragHeading(slop)

    override fun start(key: String) {
        dragging = key
        offset = 0f
        heading = DragHeading(slop)
    }

    override fun by(delta: Float) {
        heading.track(delta)
        shift(delta)
    }

    private fun shift(delta: Float) {
        val key = dragging ?: return
        val (moved, left) = ClassTableListRows.dragged(rows, key, offset + delta) { heights[it.key]?.toFloat() }
        rows = moved
        offset = left
    }

    override fun end() {
        val index = rows.indexOfFirst { it.key == dragging }
        dragging = null
        offset = 0f
        val moved = rows.getOrNull(index) as? ClassTableListRows.Person
        val stored = ClassTableListRows.of(people)
        // Nothing to save, or the person went while dragged: back to what the store holds,
        // which may have changed under the drag.
        if (moved == null || rows.map { it.key } == stored.map { it.key } || people.none { it.id == moved.person.id }) {
            rows = stored
            return
        }
        val (placement, before) = ClassTableListRows.target(rows, index)
        actions.onMove(moved.person.id, placement, before)
    }

    /**
     * How far to scroll the list this frame: [speed] down while the dragged row's middle is within
     * [edge] of the list's bottom, up within [edge] of its top, else 0. Only while the finger heads
     * toward that edge, so a row grabbed near an edge stays until dragged to it.
     */
    fun edgeStep(edge: Float, speed: Float): Float {
        val index = rows.indexOfFirst { it.key == dragging }.takeIf { it >= 0 } ?: return 0f
        val top = padding + rows.take(index).sumOf { heights[it.key] ?: 0 } - scroll.value + offset
        val middle = top + (heights[rows[index].key] ?: 0) / 2f
        return when {
            middle > viewportHeight - edge && heading.heading > 0 -> speed
            middle < edge && heading.heading < 0 -> -speed
            else -> 0f
        }
    }

    /** Scrolls the list by [step] and moves the dragged row with it, so it stays under the finger; false at an end. */
    suspend fun scroll(step: Float): Boolean {
        val used = scroll.scrollBy(step)
        if (used != 0f) shift(used)
        return used != 0f
    }

    /** What the dragged row's place on screen follows, to wait on for a change. */
    fun moves(): Any = Triple(scroll.value, offset, rows)
}

/**
 * While a dragged row is held near the list's top or bottom edge, where the finger took it,
 * scrolls the list a little each frame, moving the row with it, so a drag reaches sections off
 * screen as a list on the iPhone does. It asks for no frame otherwise, so the page goes idle while
 * the finger rests.
 */
@Composable
private fun DragEdgeScroll(drag: ListDrag) {
    val density = LocalDensity.current
    LaunchedEffect(drag.dragging) {
        if (drag.dragging == null) return@LaunchedEffect
        val edge = with(density) { 48.dp.toPx() }
        val speed = with(density) { 8.dp.toPx() }
        while (true) {
            val step = drag.edgeStep(edge, speed)
            if (step != 0f) {
                withFrameNanos { }
                if (drag.scroll(step)) continue
            }
            // Nothing to scroll: wait for the finger or the list to move.
            snapshotFlow { drag.moves() }.drop(1).first()
        }
    }
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
