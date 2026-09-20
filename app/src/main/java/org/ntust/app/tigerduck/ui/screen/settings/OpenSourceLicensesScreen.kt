package org.ntust.app.tigerduck.ui.screen.settings

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.NoTopBarInsets
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import javax.inject.Inject

/** [LicenseDetailScreen]'s key for TigerDuck's own licence. */
const val APP_LICENSE_KEY = "app"

/** Marks a [LicenseDetailScreen] key as an index into the watch app's list rather than the phone's. */
private const val WEAR_KEY_PREFIX = "w"

/** The licence name, not translated: it is the title of a legal text. */
private const val APP_LICENSE_NAME = "GNU Affero General Public License v3.0"
private const val APP_SOURCE_URL = "https://github.com/tigerduck-app/tigerduck-app-android"

@HiltViewModel
class OpenSourceLicensesViewModel @Inject constructor(
    val appState: AppState,
    repository: LicenseRepository,
) : ViewModel() {
    // LicenseRepository.load never throws: an exception here would fail the
    // stateIn coroutine and reach the uncaught handler, taking the process
    // down over a licence list that wouldn't parse.
    val licenses: StateFlow<Licenses?> = flow { emit(repository.load()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

/**
 * 開源授權, behind the row of that name on [AboutOthersScreen]: every
 * licence this build is under or ships — TigerDuck's own first, then one row
 * per [LicenseEntry] from the flavor's generated list, so the fdroid build
 * lists no Google Play components. iOS has the same page.
 */
@Composable
fun OpenSourceLicensesScreen(
    onBack: () -> Unit,
    onOpenLicense: (key: String) -> Unit,
    viewModel: OpenSourceLicensesViewModel = hiltViewModel(),
) {
    val licenses by viewModel.licenses.collectAsStateWithLifecycle()
    // Read here, not inside an item: the list body is not a composable.
    val appName = stringResource(R.string.app_name)

    LicenseScaffold(title = stringResource(R.string.settings_open_source_licenses), onBack = onBack) {
        val entries = licenses?.entries.orEmpty()
        // TigerDuck's own licence, and next to it anything TigerDuck
        // publishes separately — name-abbr is MIT, not the app's AGPL, and
        // filed under "third-party" it would be both wrong and unfindable.
        licenseCard(
            rows = listOf(LicenseRowItem(APP_LICENSE_KEY, appName, APP_LICENSE_NAME)) +
                entries.withIndex().filter { it.value.firstParty }
                    .map { (index, entry) -> entry.row(index.toString()) },
            onOpenLicense = onOpenLicense,
        )
        licenseSection(
            titleRes = R.string.settings_licenses_section_third_party,
            rows = entries.withIndex().filterNot { it.value.firstParty }
                .map { (index, entry) -> entry.row(index.toString()) },
            onOpenLicense = onOpenLicense,
        )
        // The watch app has no licence page of its own; see [Licenses.wearEntries].
        licenseSection(
            titleRes = R.string.settings_licenses_section_wear,
            rows = licenses?.wearEntries.orEmpty()
                .mapIndexed { index, entry -> entry.row("$WEAR_KEY_PREFIX$index") },
            onOpenLicense = onOpenLicense,
        )
        // The generated lists are read once at startup, and when they cannot
        // be read [LicenseRepository] still returns the app's own licence so
        // this page is never blank. Say why the rest is missing rather than
        // let the absence read as "TigerDuck ships nothing else": the
        // attribution MIT and BSD ask for is owed either way. Null is the
        // load still running, which is not the same thing.
        if (licenses != null && entries.isEmpty()) {
            item(key = "unavailable") {
                Text(
                    text = stringResource(R.string.settings_licenses_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** One row of a licence card, carried far enough out to be its own lazy item. */
private data class LicenseRowItem(val key: String, val title: String, val subtitle: String)

private fun LicenseEntry.row(key: String) =
    LicenseRowItem(key, title, licenseNames.joinToString(", "))

private fun LazyListScope.licenseSection(
    @StringRes titleRes: Int,
    rows: List<LicenseRowItem>,
    onOpenLicense: (key: String) -> Unit,
) {
    if (rows.isEmpty()) return
    item(key = "section:$titleRes") { SectionHeader(stringResource(titleRes)) }
    licenseCard(rows, onOpenLicense)
}

/**
 * The rows of one card, each its own lazy item keyed by its licence key.
 * Emitted as a single item the list was lazy in name only: the third-party
 * section alone is around eighty rows, and every one of them composed and
 * measured in the frame the data arrived in.
 */
private fun LazyListScope.licenseCard(
    rows: List<LicenseRowItem>,
    onOpenLicense: (key: String) -> Unit,
) {
    itemsIndexed(rows, key = { _, row -> row.key }) { position, row ->
        CardSlice(first = position == 0, last = position == rows.lastIndex) {
            Column {
                LicenseRow(
                    title = row.title,
                    subtitle = row.subtitle,
                    onClick = { onOpenLicense(row.key) },
                )
                if (position < rows.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
    }
}

/**
 * One slice of a card that is drawn an item at a time. [ContentCard] cannot
 * be split across lazy items, so the items carry the card between them: the
 * outer corners are rounded on the first and the last, the ones in between
 * are square, and the card's own margins land on the ends. What the user
 * sees is one card; what the list composes is one screenful.
 */
@Composable
private fun CardSlice(first: Boolean, last: Boolean, content: @Composable () -> Unit) {
    // The shape ContentCard's Card takes from the theme.
    val card = MaterialTheme.shapes.medium
    val square = CornerSize(0.dp)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = card.copy(
            topStart = if (first) card.topStart else square,
            topEnd = if (first) card.topEnd else square,
            bottomStart = if (last) card.bottomStart else square,
            bottomEnd = if (last) card.bottomEnd else square,
        ),
        modifier = Modifier
            .fillMaxWidth()
            // ContentCard's padding, split so the gap above and below the
            // card falls outside it rather than between its rows.
            .padding(horizontal = 16.dp)
            .padding(
                top = if (first) 4.dp else 0.dp,
                bottom = if (last) 4.dp else 0.dp,
            ),
        content = content,
    )
}

/**
 * One licence in full: TigerDuck's own for [APP_LICENSE_KEY], otherwise the
 * [LicenseEntry] at that index — its artifacts, who publishes them, the text
 * of each licence they ship under, and any notice they carry inside their
 * own artifact. Google's SDK terms publish no text to embed, so those link
 * out instead.
 */
@Composable
fun LicenseDetailScreen(
    key: String,
    onBack: () -> Unit,
    viewModel: OpenSourceLicensesViewModel = hiltViewModel(),
) {
    val licenses by viewModel.licenses.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val browserPreference = viewModel.appState.browserPreference
    val open: (String) -> Unit = { url -> openUrl(context, url, browserPreference) }

    if (key == APP_LICENSE_KEY) {
        val appLicense = licenses?.appLicense
        val paragraphs = remember(appLicense) {
            if (appLicense.isNullOrBlank()) emptyList() else paragraphsOf(appLicense)
        }
        LicenseScaffold(title = stringResource(R.string.app_name), onBack = onBack, selectable = true) {
            item {
                ContentCard {
                    SettingsLinkRow(stringResource(R.string.settings_view_source_code)) { open(APP_SOURCE_URL) }
                }
            }
            licenseText(APP_LICENSE_NAME, paragraphs)
        }
        return
    }

    val entry = if (key.startsWith(WEAR_KEY_PREFIX)) {
        key.removePrefix(WEAR_KEY_PREFIX).toIntOrNull()?.let { licenses?.wearEntries?.getOrNull(it) }
    } else {
        key.toIntOrNull()?.let { licenses?.entries?.getOrNull(it) }
    }
    val blocks = remember(entry) { entry?.let(::licenseBlocks).orEmpty() }
    LicenseScaffold(title = entry?.title.orEmpty(), onBack = onBack, selectable = true) {
        if (entry != null) entryDetail(entry, blocks, open)
    }
}

/**
 * A block below an entry's header: a licence that publishes no text to
 * embed and links out instead, or one shown in full.
 *
 * Built once, outside the list — [remember] inside a lazy item is thrown
 * away when the item scrolls off, so the AGPL was being reflowed on the
 * main thread every time the user scrolled back up to it.
 */
private sealed interface LicenseBlock {
    data class Linked(val license: LicenseText) : LicenseBlock
    data class Body(val title: String, val paragraphs: List<String>) : LicenseBlock
}

private fun licenseBlocks(entry: LicenseEntry): List<LicenseBlock> =
    entry.texts.map { license ->
        val content = license.content
        if (content.isNullOrBlank()) {
            LicenseBlock.Linked(license)
        } else {
            LicenseBlock.Body(license.name, paragraphsOf(content))
        }
    } +
        // What the artifact itself carries, after the licence it is
        // published under: the notice Apache-2.0 asks be passed on, or the
        // licences of the code compiled into a closed SDK. Grouped by text,
        // because Play Services names the same one sixteen times over.
        LicenseCatalog.groupNotices(entry.notices)
            .map { LicenseBlock.Body(it.name, paragraphsOf(it.content)) }

/**
 * The reflowed text, split at its blank lines so the list can lay out a
 * paragraph at a time. Every paragraph but the first keeps the blank line
 * that preceded it, so the spacing — and a selection copied out of the
 * page — read exactly as they did while this was one string.
 */
private fun paragraphsOf(text: String): List<String> =
    LicenseCatalog.reflow(text).split("\n\n")
        .mapIndexed { index, paragraph -> if (index == 0) paragraph else "\n$paragraph" }

private fun LazyListScope.entryDetail(
    entry: LicenseEntry,
    blocks: List<LicenseBlock>,
    open: (String) -> Unit,
) {
    item {
        ContentCard {
            Column(modifier = Modifier.padding(16.dp)) {
                entry.note?.let { note ->
                    Text(note, style = MaterialTheme.typography.bodyMedium)
                }
                if (entry.holders.isNotEmpty()) {
                    Text(
                        entry.holders.joinToString(", "),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = if (entry.note == null) 0.dp else 8.dp),
                    )
                }
                entry.artifacts.forEach { artifact ->
                    Text(
                        artifact,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                    )
                }
            }
        }
    }
    entry.website?.let { website ->
        item {
            ContentCard {
                SettingsLinkRow(stringResource(R.string.settings_licenses_website)) { open(website) }
            }
        }
    }
    blocks.forEach { block ->
        when (block) {
            is LicenseBlock.Linked -> linkedLicense(block.license, open)
            is LicenseBlock.Body -> licenseText(block.title, block.paragraphs)
        }
    }
}

/**
 * A licence or notice in full, a lazy item per paragraph: as one item it
 * was one [Text] measured at unbounded height, which for the AGPL's 34 KB
 * is a long frame every time it comes back on screen.
 */
private fun LazyListScope.licenseText(title: String, paragraphs: List<String>) {
    if (paragraphs.isEmpty()) return
    item { SectionHeader(title, modifier = Modifier.padding(top = 12.dp)) }
    itemsIndexed(paragraphs) { position, paragraph ->
        CardSlice(first = position == 0, last = position == paragraphs.lastIndex) {
            Text(
                paragraph,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = if (position == 0) 16.dp else 0.dp,
                    bottom = if (position == paragraphs.lastIndex) 16.dp else 0.dp,
                ),
            )
        }
    }
}

private fun LazyListScope.linkedLicense(license: LicenseText, open: (String) -> Unit) {
    val url = license.url ?: return
    item {
        ContentCard(modifier = Modifier.padding(top = 12.dp)) {
            SettingsLinkRow(license.name) { open(url) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicenseScaffold(
    title: String,
    onBack: () -> Unit,
    // Wraps the whole list in one SelectionContainer. A licence is a lazy
    // item per paragraph now, and a container per item would stop a
    // selection at the end of whichever paragraph it started in.
    selectable: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = NoTopBarInsets,
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                expandedHeight = SubSettingsBarHeight,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { scaffoldPadding ->
        SelectableWhen(selectable) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(scaffoldPadding),
                contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun SelectableWhen(enabled: Boolean, content: @Composable () -> Unit) {
    if (enabled) SelectionContainer(content = content) else content()
}

@Composable
private fun LicenseRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.DISABLED),
            modifier = Modifier.size(18.dp),
        )
    }
}

private fun openUrl(context: Context, url: String, browserPreference: String) {
    val uri = url.toUri()
    if (browserPreference == "inApp") {
        CustomTabsIntent.Builder().build().launchUrl(context, uri)
    } else {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
}
