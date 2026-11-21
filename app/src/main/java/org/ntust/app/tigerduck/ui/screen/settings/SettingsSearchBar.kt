package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.PageSettings
import org.ntust.app.tigerduck.ui.component.SearchFieldIcon
import org.ntust.app.tigerduck.ui.component.SearchRevealState
import org.ntust.app.tigerduck.ui.component.searchClearIcon

/**
 * Settings' search field, which sits in the drawer under the title as iOS's search bar does.
 * [onFocusChanged] pins the drawer open: the field must not be scrolled away from under someone
 * who is typing in it.
 */
@Composable
internal fun SettingsSearchField(query: String, onQueryChange: (String) -> Unit, onFocusChanged: (Boolean) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .onFocusChanged { onFocusChanged(it.isFocused) },
        leadingIcon = { SearchFieldIcon() },
        trailingIcon = searchClearIcon(query) { onQueryChange("") },
        placeholder = { Text(stringResource(R.string.action_search)) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    )
}

/**
 * Pulls the search drawer open, as iOS pulls its search bar down: a drag past the top of [list]
 * opens it, and the list scrolling on shuts it first while it is in view. A finger let go part way
 * sends it the rest of the way.
 */
internal class SearchPull(private val reveal: SearchRevealState, private val list: LazyListState) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (available.y < 0f && list.firstVisibleItemIndex <= DRAWER_INDEX) Offset(0f, reveal.consume(available.y)) else Offset.Zero

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (available.y > 0f && source == NestedScrollSource.UserInput) Offset(0f, reveal.consume(available.y)) else Offset.Zero

    override suspend fun onPreFling(available: Velocity): Velocity {
        reveal.settle()
        return Velocity.Zero
    }

    companion object {
        /** The drawer's place in the list, under the title. */
        const val DRAWER_INDEX = 1
    }
}

/**
 * The rows [query] finds, each read `Page › Setting`: one on a page opens it with [onOpenPage],
 * and one on the root goes back to the list at it with [onReveal]. None found shows nothing.
 */
@Composable
internal fun SettingsSearchResults(
    query: String,
    state: SettingsState,
    onOpenPage: (PageSettings) -> Unit,
    onReveal: (SettingsRow) -> Unit,
) {
    val context = LocalContext.current
    val locales = LocalConfiguration.current.locales
    val results = remember(query, state, locales) { SettingsSearch.results(query, state, context::getString) }
    if (results.isEmpty()) return
    ContentCard {
        Column {
            results.forEachIndexed { index, result ->
                if (index > 0) HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = SettingRowHeight)
                        .clickable(role = Role.Button) {
                            when (val place = result.row.place) {
                                is SettingsPlace.Page -> onOpenPage(place.page)
                                is SettingsPlace.Section -> onReveal(result.row)
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(result.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
