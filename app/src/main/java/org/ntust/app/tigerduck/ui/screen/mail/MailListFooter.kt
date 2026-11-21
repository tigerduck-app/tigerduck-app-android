package org.ntust.app.tigerduck.ui.screen.mail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.mail.SchoolMailRepository
import org.ntust.app.tigerduck.mail.index.MailIndexProgress
import java.text.NumberFormat
import java.util.Locale

/**
 * The end of the mail list, as the iPhone's (tigerduck-app/tigerduck-app#996): a spinner while a
 * further page is on its way ([busy]), and, after a search that may have missed mail
 * ([searchIndexPartial], kept until the folders it searched are whole), how far the download for
 * search has come -- "20/2,000", with the spinner while the download runs; the spinner alone while
 * it still counts; the count alone once it stopped, or the partial-index sentence where it stopped
 * before it had one. TalkBack reads that sentence for it, not the count.
 *
 * It collects [progress] itself, so a batch downloaded recomposes the footer, not the rows above it.
 */
@Composable
internal fun MailListFooter(progress: StateFlow<MailIndexProgress?>, searchIndexPartial: Boolean, busy: Boolean) {
    val index by progress.collectAsStateWithLifecycle()
    val shown = index?.takeIf { searchIndexPartial }
    val spins = footerSpins(shown, busy)
    if (shown == null && !spins) return
    val sentence = stringResource(R.string.school_mail_search_index_partial)
    val text = shown?.let { footerText(it, spins, sentence) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .then(if (shown != null) Modifier.clearAndSetSemantics { contentDescription = sentence } else Modifier),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (spins) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
        if (text != null) {
            Text(
                text,
                // Figures of one width, so the count does not jitter as it climbs.
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/** Whether the end of the list spins: a page on its way ([busy]), or the download [shown] under way. */
internal fun footerSpins(shown: MailIndexProgress?, busy: Boolean): Boolean =
    busy || (shown != null && shown.running && shown.total != 0)

/**
 * What the end of the list says of [shown]: its count, or the partial-index [sentence] where the
 * download stopped before it had one; nothing while it still counts, which the spinner says.
 */
internal fun footerText(shown: MailIndexProgress, spins: Boolean, sentence: String): String? =
    indexProgressText(shown) ?: sentence.takeUnless { spins }

/**
 * [this], its search no longer said to have missed mail once every folder it searched is [whole]
 * ([SchoolMailRepository.searchIndexWhole]): the run again that would say so is skipped while the
 * user edits the query or has paged the results, and another folder still downloading is none of
 * its business (#752).
 */
internal fun SchoolMailListViewModel.UiState.wholeAsOf(whole: Set<String>): SchoolMailListViewModel.UiState =
    if (searchIndexPartial && whole.containsAll(targets)) copy(searchIndexPartial = false) else this

/**
 * [progress] as the sync dot's popup and the end of the search results show it: mail downloaded
 * for search, of how many it lacked, as "120/3,456". Null while the download is still counting,
 * which has no number to show yet.
 */
internal fun indexProgressText(progress: MailIndexProgress, locale: Locale = Locale.getDefault()): String? {
    val total = progress.total ?: return null
    val number = NumberFormat.getIntegerInstance(locale)
    return number.format(progress.done) + "/" + number.format(total)
}

/** Whether [this] progress is mail downloading: under way, past the count. */
internal fun MailIndexProgress.downloading(): Boolean = running && total != null
