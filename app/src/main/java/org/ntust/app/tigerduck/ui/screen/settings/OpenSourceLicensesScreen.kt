package org.ntust.app.tigerduck.ui.screen.settings

import android.content.Context
import android.content.Intent
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

/** [LicenseDetailScreen]'s key for TigerDuck's own licence; any other key is a [LicenseEntry] index. */
const val APP_LICENSE_KEY = "app"

/** The licence name, not translated: it is the title of a legal text. */
private const val APP_LICENSE_NAME = "GNU Affero General Public License v3.0"
private const val APP_SOURCE_URL = "https://github.com/tigerduck-app/tigerduck-app-android"

@HiltViewModel
class OpenSourceLicensesViewModel @Inject constructor(
    val appState: AppState,
    repository: LicenseRepository,
) : ViewModel() {
    val licenses: StateFlow<Licenses?> = flow { emit(repository.load()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

/**
 * 開源授權, behind the row of that name on [AboutOthersScreen]: every
 * licence this build is under or ships — TigerDuck's own first, then one row
 * per [LicenseEntry] from the flavor's generated list, so the fdroid build
 * lists no Google Play components. iOS has the same page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenSourceLicensesScreen(
    onBack: () -> Unit,
    onOpenLicense: (key: String) -> Unit,
    viewModel: OpenSourceLicensesViewModel = hiltViewModel(),
) {
    val licenses by viewModel.licenses.collectAsStateWithLifecycle()

    LicenseScaffold(title = stringResource(R.string.settings_open_source_licenses), onBack = onBack) {
        item {
            ContentCard {
                LicenseRow(
                    title = stringResource(R.string.app_name),
                    subtitle = APP_LICENSE_NAME,
                    onClick = { onOpenLicense(APP_LICENSE_KEY) },
                )
            }
        }
        val entries = licenses?.entries.orEmpty()
        if (entries.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.settings_licenses_section_third_party)) }
            item {
                ContentCard {
                    Column {
                        entries.forEachIndexed { index, entry ->
                            LicenseRow(
                                title = entry.title,
                                subtitle = entry.licenseNames.joinToString(", "),
                                onClick = { onOpenLicense(index.toString()) },
                            )
                            if (index < entries.lastIndex) {
                                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
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
        LicenseScaffold(title = stringResource(R.string.app_name), onBack = onBack) {
            item {
                ContentCard {
                    SettingsLinkRow(stringResource(R.string.settings_view_source_code)) { open(APP_SOURCE_URL) }
                }
            }
            licenses?.let { licenseText(APP_LICENSE_NAME, it.appLicense) }
        }
        return
    }

    val entry = key.toIntOrNull()?.let { licenses?.entries?.getOrNull(it) }
    LicenseScaffold(title = entry?.title.orEmpty(), onBack = onBack) {
        if (entry != null) entryDetail(entry, open)
    }
}

private fun LazyListScope.entryDetail(entry: LicenseEntry, open: (String) -> Unit) {
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
    entry.texts.forEach { license ->
        if (license.content.isNullOrBlank()) linkedLicense(license, open) else licenseText(license.name, license.content)
    }
    // What the artifact itself carries, after the licence it is published
    // under: the notice Apache-2.0 asks be passed on, or the licences of the
    // code compiled into a closed SDK.
    entry.notices.forEach { notice -> licenseText(notice.name, notice.content) }
}

private fun LazyListScope.licenseText(name: String, text: String) {
    item { SectionHeader(name, modifier = Modifier.padding(top = 12.dp)) }
    item {
        val reflowed = remember(text) { LicenseCatalog.reflow(text) }
        ContentCard {
            SelectionContainer {
                Text(
                    reflowed,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
            content = content,
        )
    }
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
