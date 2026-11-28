package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.storage.AppStorage.Kind
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.PageScaffold
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

/**
 * 儲存空間, under 其他設定: everything the app keeps on the phone that can be cleared, with the
 * total first. Downloaded files (Moodle's, mail attachments, class table images), each deleted at
 * once since it downloads again when next opened, and all of them together, which asks first.
 * School Mail's cache, cleared at once, and the mail downloaded for search, which takes long to
 * fetch again and asks first. The TigerDuck browser's website data, which asks first, since sites
 * may ask the student to sign in again. Each page's settings list its own of these too
 * ([PageStorageSection]), in the same rows. The TigerDuck browser's downloads are in the phone's
 * Downloads folder, the user's, and a footnote says so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(
    onBack: () -> Unit,
    viewModel: StorageViewModel = hiltViewModel(),
) {
    val sizes by viewModel.sizes.collectAsStateWithLifecycle()
    // Measured each time the page shows, as files and mail come and go behind it.
    LifecycleResumeEffect(viewModel) {
        viewModel.measure()
        onPauseOrDispose { }
    }
    var asking by rememberSaveable { mutableStateOf<StorageQuestion?>(null) }
    val clear = { kind: Kind -> kind.question?.let { asking = it } ?: viewModel.clear(kind) }

    PageScaffold(
        title = stringResource(R.string.settings_storage_title),
        onBack = onBack,
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        ) {
            item {
                ContentCard { SizeRow(stringResource(R.string.settings_storage_total), sizes?.values?.sum()) }
            }

            item { Spacer(Modifier.height(24.dp)) }

            item { SectionHeader(stringResource(R.string.settings_downloaded_files_title)) }
            item {
                ContentCard(modifier = Modifier.padding(top = 4.dp)) {
                    Column {
                        Kind.entries.filter { it.downloaded }.forEach { kind ->
                            KindRow(kind, sizes?.get(kind)) { clear(kind) }
                            HorizontalDivider()
                        }
                        DestructiveRow(
                            stringResource(R.string.settings_downloaded_files_delete_all),
                            enabled = (sizes?.filterKeys { it.downloaded }?.values?.sum() ?: 0L) > 0L,
                            onClick = { asking = StorageQuestion.DOWNLOADED_FILES },
                        )
                    }
                }
            }
            item { Footnote(stringResource(R.string.settings_downloaded_files_footer)) }

            kindSection(R.string.feature_school_mail, listOf(Kind.CACHED_MAIL, Kind.DOWNLOADED_MAIL), sizes, clear)
            item { Footnote(stringResource(R.string.school_mail_storage_footer)) }

            kindSection(R.string.settings_browser_in_app, listOf(Kind.WEBSITE_DATA), sizes, clear)
        }
    }

    asking?.let { question ->
        StorageQuestionDialog(
            question,
            onConfirm = {
                asking = null
                question.kind?.let(viewModel::clear) ?: viewModel.clearDownloaded()
            },
            onDismiss = { asking = null },
        )
    }
}

/** A gap, [title] and a card of [kinds]' rows, each cleared through [clear]. */
private fun LazyListScope.kindSection(
    title: Int,
    kinds: List<Kind>,
    sizes: Map<Kind, Long>?,
    clear: (Kind) -> Unit,
) {
    item { Spacer(Modifier.height(16.dp)) }
    item { SectionHeader(stringResource(title)) }
    item {
        ContentCard(modifier = Modifier.padding(top = 4.dp)) {
            Column {
                kinds.forEachIndexed { index, kind ->
                    if (index > 0) HorizontalDivider()
                    KindRow(kind, sizes?.get(kind)) { clear(kind) }
                }
            }
        }
    }
}

@Composable
private fun Footnote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
}
