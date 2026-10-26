package org.ntust.app.tigerduck.ui.component

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R

/**
 * A search field's leading icon, for its `leadingIcon`: the magnifier, which turns into a spinner
 * while [searching], so a search that waits on the network shows it is under way. Every search
 * field takes it, so all of them look alike, and one whose search becomes slow shows it.
 */
@Composable
fun SearchFieldIcon(searching: Boolean = false) {
    if (searching) {
        val label = stringResource(R.string.add_course_searching)
        // The icon's own size and colour, so the field does not shift as it turns. Said aloud as
        // it appears, as the field keeps the focus and the spinner would otherwise pass unheard.
        CircularProgressIndicator(
            modifier = Modifier
                .size(20.dp)
                .semantics {
                    contentDescription = label
                    liveRegion = LiveRegionMode.Polite
                },
            strokeWidth = 2.dp,
            color = LocalContentColor.current,
        )
    } else {
        Icon(Icons.Filled.Search, contentDescription = null)
    }
}
