package org.ntust.app.tigerduck.ui.screen.coursemoodle

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.data.moodle.MoodleCourseStore
import org.ntust.app.tigerduck.data.moodle.MoodleDepartment
import org.ntust.app.tigerduck.data.moodle.MoodleMemberPlacement
import org.ntust.app.tigerduck.data.moodle.MoodleMemberSection
import org.ntust.app.tigerduck.data.moodle.MoodleMembers
import org.ntust.app.tigerduck.data.moodle.departmentKey
import org.ntust.app.tigerduck.data.moodle.name
import org.ntust.app.tigerduck.data.moodle.studentId
import org.ntust.app.tigerduck.network.model.moodle.MoodleMember
import org.ntust.app.tigerduck.ui.component.EmptyStateView
import org.ntust.app.tigerduck.ui.component.SearchFieldIcon
import org.ntust.app.tigerduck.ui.component.searchClearIcon
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import java.text.NumberFormat

/**
 * The people of a course the account can see, in sections by role. The search is a row of the
 * list above its top, which a pull on the list brings down, as on iOS; it matches on the device
 * and asks Moodle nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodleMembersScreen(
    onBack: () -> Unit,
    onOpenRoute: (String) -> Unit,
    viewModel: MoodleCourseViewModel = hiltViewModel(),
) {
    val store by viewModel.store.collectAsStateWithLifecycle()
    MoodleSignedOutEffect(viewModel, onBack)
    var query by rememberSaveable { mutableStateOf("") }
    val current = store
    Scaffold(
        topBar = {
            MoodleTopBar(
                title = stringResource(R.string.course_detail_members_label),
                onBack = onBack,
                moodlePath = current?.let { MoodlePage.participants(it.courseId) },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (current == null) {
                MoodleFirstLoad(null, {})
            } else {
                MembersList(
                    store = current,
                    query = query,
                    onQueryChange = { query = it },
                    onOpen = { onOpenRoute(MoodleRoutes.member(current.courseId, it.id)) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MembersList(
    store: MoodleCourseStore,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpen: (MoodleMember) -> Unit,
) {
    val state by store.members.state.collectAsStateWithLifecycle()
    val pictures by store.pictures.collectAsStateWithLifecycle()
    val departmentNames = departmentShortNames()
    MoodleAutoRefresh(store, store.members.ttlMs, store::awaitCache, { store.members.state.value.fetchedAtMs }) {
        store.refreshMembers()
    }
    var pulling by remember { mutableStateOf(false) }
    val refresher = rememberRefresher { store.refreshMembers() }
    val scope = rememberCoroutineScope()
    val list = state.value
    val sections = remember(list, query, departmentNames) {
        list?.let { MoodleMembers.sections(it.members, query, departmentNames = departmentNames) }.orEmpty()
    }

    // The search row is the list's first item and the list opens on its second, so the row waits
    // above the top: a short pull scrolls it into view, a longer one goes on to the refresh, and
    // the next scroll up puts it away -- iOS's pull-to-reveal, as Material's list has it. Opening
    // on the second item holds only while there is one: a first fetch composes the spinner alone,
    // which puts the position back on the first, so the row is put away once the list arrives.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = 1)
    var searchPutAway by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(list != null) {
        if (list != null && !searchPutAway) {
            searchPutAway = true
            listState.scrollToItem(1)
        }
    }

    // The rows below the search row, built in composition so that RowsBelowSearch learns of a
    // change to them ahead of the measure pass that lays them out; see its note.
    val failure = state.failureBesideCopy
    val noMatches = sections.isEmpty() && query.isNotBlank()
    val rowsBelow = remember(sections, failure, noMatches) {
        buildList {
            if (failure != null) add(MembersRow.Failure(failure))
            if (noMatches) add(MembersRow.NoMatches)
            sections.forEach { section ->
                add(MembersRow.Header(section))
                section.members.forEachIndexed { index, member -> add(MembersRow.Person(section, index, member)) }
            }
        }
    }
    val rowKeys = remember(rowsBelow) { rowsBelow.map { it.key } }

    PullToRefreshBox(
        isRefreshing = pulling,
        onRefresh = { pulling = true; refresher { pulling = false } },
        modifier = Modifier.fillMaxSize(),
    ) {
        // The viewport's height, known before the list's first measure, for RowsBelowSearch.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val paddingPx = with(LocalDensity.current) { MembersListVerticalPadding.roundToPx() }
            val minRowsPx = if (constraints.hasBoundedHeight) (constraints.maxHeight - 2 * paddingPx).coerceAtLeast(0) else 0
            val filler = remember { RowsBelowSearch() }
            // Once this composition is in, before its layout: the filler's measure is then
            // invalidated for the rows this pass lays out, not the ones before.
            SideEffect { filler.rows(rowKeys) }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = MembersListVerticalPadding),
            ) {
                if (list == null) {
                    item {
                        MoodleFirstLoad(
                            failure = if (state.isRefreshing) null else state.failure,
                            onRetry = { scope.launch { store.refreshMembers() } },
                        )
                    }
                    return@LazyColumn
                }
                // Before everything the query decides, so a query that empties the list keeps the
                // field, and the keyboard with it.
                item(key = "search") { SearchRow(query, onQueryChange) }
                // One item per row, so a large class draws only the rows on screen, and only they
                // fetch their pictures.
                items(rowsBelow, key = { it.key }) { row ->
                    Box(filler.measured(row.key)) {
                        when (row) {
                            is MembersRow.Failure -> MoodleRefreshFailure(
                                row.failure,
                                onRetry = { scope.launch { store.refreshMembers() } },
                                modifier = Modifier.padding(bottom = 12.dp),
                            )
                            MembersRow.NoMatches -> EmptyStateView(
                                icon = Icons.Filled.SearchOff,
                                title = stringResource(R.string.desktop_bulletins_no_matches),
                                message = null,
                            )
                            is MembersRow.Header -> MemberSectionHeader(row.section)
                            is MembersRow.Person -> MemberCardRow(
                                store = store,
                                member = row.member,
                                picture = row.member.pictureUrl?.let { pictures[it] },
                                isMe = row.member.id == list.ownId,
                                first = row.index == 0,
                                last = row.index == row.section.members.lastIndex,
                                onOpen = onOpen,
                            )
                        }
                    }
                }
                item(key = "filler") { Spacer(filler.filling(minRowsPx)) }
            }
        }
    }
}

/** A row below the search row, by what it shows; [key] is its list item's. */
private sealed interface MembersRow {
    val key: Any

    class Failure(val failure: Throwable) : MembersRow {
        override val key get() = "failure"
    }

    object NoMatches : MembersRow {
        override val key get() = "none"
    }

    class Header(val section: MoodleMemberSection) : MembersRow {
        override val key get() = "section-${section.placement}"
    }

    class Person(val section: MoodleMemberSection, val index: Int, val member: MoodleMember) : MembersRow {
        override val key get() = "member-${member.id}"
    }
}

/**
 * The rows below the search row, and a filler after them that keeps them at least a viewport
 * tall together. Without it a class too short to fill the screen cannot open past the search
 * row: the list scrolls back to fill the space, and the row shows at rest.
 *
 * The filler is sized in the measure pass, from the heights of the rows measured just before it
 * in that same pass, so the very first layout is already tall enough and the opening position
 * holds; the list's own layout info would say so a frame late, after the position had been
 * clamped and kept. Both things it reads are snapshot state, so its measure is never reused as it
 * was when they have moved: the row keys, set from composition by [rows] so that a query which
 * takes rows away or brings them back invalidates the filler ahead of the pass that lays them
 * out (too little filler after rows went would let the clamp land for good; too much after rows
 * came would leave blank room to scroll into), and the heights, so that a row which changes
 * height does the same.
 */
private class RowsBelowSearch {
    private val keys = mutableStateOf<List<Any>>(emptyList())
    private val heights = mutableStateMapOf<Any, Int>()

    /** The rows below the search row, in order, as composition has them. */
    fun rows(keys: List<Any>) {
        if (this.keys.value == keys) return
        this.keys.value = keys
        // A row the list no longer has must not count towards the filler.
        heights.keys.retainAll(keys.toSet())
    }

    /** On a row: records its height for the filler. */
    fun measured(key: Any): Modifier = Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        // Only on a change: a needless write here would invalidate the filler's measure for nothing.
        if (heights[key] != placeable.height) heights[key] = placeable.height
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    /** On the filler: [minPx] less the rows' heights, and nothing once they fill it. */
    fun filling(minPx: Int): Modifier = Modifier
        .fillMaxWidth()
        .layout { measurable, constraints ->
            val height = (minPx - keys.value.sumOf { heights[it] ?: 0 }).coerceAtLeast(0)
            val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
}

/** The list's padding above its first item and below its last, and the search row's below the field. */
private val MembersListVerticalPadding = 12.dp

/**
 * The search row. It asks for no focus of its own: it comes into view by a scroll, and a keyboard
 * rising on every pull would cover the list the pull was for.
 */
@Composable
private fun SearchRow(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.action_search)) },
        leadingIcon = { SearchFieldIcon() },
        trailingIcon = searchClearIcon(query) { onQueryChange("") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = RoundedCornerShape(28.dp),
        // As much room below the field as the list keeps above its first item: the list draws
        // under that padding, and with less the field's bottom edge would show through it at rest.
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = MembersListVerticalPadding),
    )
}

@Composable
private fun MemberSectionHeader(section: MoodleMemberSection) {
    val title = placementTitle(section.placement)
    val count = remember(section.members.size) { NumberFormat.getIntegerInstance().format(section.members.size) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 8.dp)
            .clearAndSetSemantics {
                heading()
                contentDescription = title
                stateDescription = count
            },
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = count,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
        )
    }
}

/** A row of a section's card: the card's corners round at its first and last row. */
@Composable
private fun MemberCardRow(
    store: MoodleCourseStore,
    member: MoodleMember,
    picture: androidx.compose.ui.graphics.ImageBitmap?,
    isMe: Boolean,
    first: Boolean,
    last: Boolean,
    onOpen: (MoodleMember) -> Unit,
) {
    LaunchedEffect(member.pictureUrl) { store.loadPicture(member) }
    val corner = 16.dp
    val shape = RoundedCornerShape(
        topStart = if (first) corner else 0.dp,
        topEnd = if (first) corner else 0.dp,
        bottomStart = if (last) corner else 0.dp,
        bottomEnd = if (last) corner else 0.dp,
    )
    Surface(shape = shape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column {
            if (!first) {
                HorizontalDivider(modifier = Modifier.padding(start = 72.dp), color = cardDividerColor())
            }
            MemberRow(member, picture, isMe) { onOpen(member) }
        }
    }
}

/**
 * A person in the list: picture, name, and student ID and department, the department by its short
 * name. The signed-in student's own row ([isMe]) has a "Me" tag after the name, which TalkBack
 * reads with it, as on the iPhone.
 */
@Composable
private fun MemberRow(member: MoodleMember, picture: androidx.compose.ui.graphics.ImageBitmap?, isMe: Boolean, onClick: () -> Unit) {
    val department = member.departmentKey?.let { departmentNameRes(it, short = true) }?.let { stringResource(it) }
    val subline = listOfNotNull(member.studentId, department).joinToString(" · ")
    val me = stringResource(R.string.course_member_me)
    ListItem(
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = if (isMe) "${member.name}, $me" else member.name
                if (subline.isNotEmpty()) stateDescription = subline
            },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        leadingContent = { MoodleAvatar(member.name, picture, 40.dp) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Cut short before the tag is: the tag says whose row it is.
                Text(member.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (isMe) {
                    Spacer(Modifier.width(6.dp))
                    MeTag(me)
                }
            }
        },
        // The department comes from the ID, so there is none without one.
        supportingContent = member.studentId?.let { studentId -> { MemberSubline(studentId, department) } },
        trailingContent = {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.DISABLED),
            )
        },
    )
}

/** The "Me" after the signed-in student's name, in the accent colour as the interests on a member's page. */
@Composable
private fun MeTag(text: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * The line under a name in the list: the student ID, whole, then the department's short name, which
 * is the one cut short when the line runs out of room, as on the iPhone.
 *
 * The two share one baseline. Set from the top, a Chinese department's font sits its baseline lower
 * than the ID's digits, and Material's ListItem reads a supporting line whose first and last
 * baselines differ as two lines of text: it then lays the row out as a three-line one, taller and
 * from the top.
 */
@Composable
internal fun MemberSubline(studentId: String, department: String?) {
    Row {
        Text(studentId, maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
        if (department != null) {
            Text(" · $department", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.alignByBaseline())
        }
    }
}

@Composable
internal fun placementTitle(placement: MoodleMemberPlacement): String = when (placement) {
    MoodleMemberPlacement.Instructors -> stringResource(R.string.course_detail_instructor_label)
    MoodleMemberPlacement.Assistants -> stringResource(R.string.course_members_tas)
    is MoodleMemberPlacement.Other -> placement.name
    MoodleMemberPlacement.Auditors -> stringResource(R.string.course_members_auditors)
    MoodleMemberPlacement.Students -> stringResource(R.string.course_members_students)
}

/**
 * The app-translation string naming the department [key] ([MoodleDepartment]): the school's
 * official name for a member's page, or the [short] one for the members list; null for a key it
 * has none for. Chinese names in Chinese, the English name everywhere else (#697).
 */
@StringRes
internal fun departmentNameRes(key: String, short: Boolean): Int? = when (key) {
    "ad" -> if (short) R.string.course_member_department_ad_short else R.string.course_member_department_ad
    "ba" -> if (short) R.string.course_member_department_ba_short else R.string.course_member_department_ba
    "ch" -> if (short) R.string.course_member_department_ch_short else R.string.course_member_department_ch
    "cs" -> if (short) R.string.course_member_department_cs_short else R.string.course_member_department_cs
    "ct" -> if (short) R.string.course_member_department_ct_short else R.string.course_member_department_ct
    "dt" -> if (short) R.string.course_member_department_dt_short else R.string.course_member_department_dt
    "ee" -> if (short) R.string.course_member_department_ee_short else R.string.course_member_department_ee
    "et" -> if (short) R.string.course_member_department_et_short else R.string.course_member_department_et
    "hc" -> if (short) R.string.course_member_department_hc_short else R.string.course_member_department_hc
    "im" -> if (short) R.string.course_member_department_im_short else R.string.course_member_department_im
    "me" -> if (short) R.string.course_member_department_me_short else R.string.course_member_department_me
    "tx" -> if (short) R.string.course_member_department_tx_short else R.string.course_member_department_tx
    else -> null
}

/** The short names the list shows, by department key, so its search matches what its rows say. */
@Composable
internal fun departmentShortNames(): Map<String, String> {
    val names = mutableMapOf<String, String>()
    for (key in MoodleDepartment.keys) {
        val res = departmentNameRes(key, short = true) ?: continue
        names[key] = stringResource(res)
    }
    return names
}
