// A page's own storage, listed on its page of Settings as the iPhone lists it, rather than a row
// that leads to the Storage page (Sam's outline, 2026-11-29). The Storage page keeps them all, its
// total first.

package org.ntust.app.tigerduck.ui.screen.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.storage.AppStorage.Kind
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.PageSettings

/** What the page keeps on the phone, in the order its Storage section lists it; none for most. */
internal val PageSettings.storageKinds: List<Kind>
    get() = when (this) {
        PageSettings.CLASS_TABLE -> listOf(Kind.CLASS_TABLE_IMAGES)
        PageSettings.MAIL -> listOf(Kind.MAIL_ATTACHMENTS, Kind.CACHED_MAIL, Kind.DOWNLOADED_MAIL)
        PageSettings.BROWSER -> listOf(Kind.WEBSITE_DATA)
        PageSettings.MOODLE -> listOf(Kind.MOODLE_FILES)
        PageSettings.HOME, PageSettings.BULLETINS, PageSettings.LIBRARY -> emptyList()
    }

/**
 * The Storage page's footnote that speaks for the page's storage: the mail downloaded for search,
 * and where the browser's downloads go instead.
 */
@get:StringRes
private val PageSettings.storageFootnote: Int?
    get() = when (this) {
        PageSettings.MAIL -> R.string.school_mail_storage_footer
        PageSettings.BROWSER -> R.string.settings_downloaded_files_footer
        else -> null
    }

/**
 * [page]'s Storage section: a size for each of its [storageKinds], measured each time the page
 * shows, with its button, which clears it at once or asks first, as on the Storage page.
 */
@Composable
internal fun PageStorageSection(page: PageSettings, viewModel: StorageViewModel = hiltViewModel()) {
    val sizes by viewModel.sizes.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.measure()
        onPauseOrDispose { }
    }
    var asking by rememberSaveable { mutableStateOf<StorageQuestion?>(null) }
    SettingsSectionTitle(stringResource(R.string.settings_storage_title))
    ContentCard {
        Column {
            page.storageKinds.forEachIndexed { index, kind ->
                if (index > 0) HorizontalDivider()
                KindRow(kind, sizes?.get(kind)) { kind.question?.let { asking = it } ?: viewModel.clear(kind) }
            }
        }
    }
    page.storageFootnote?.let { PageFootnote(stringResource(it)) }
    asking?.let { question ->
        StorageQuestionDialog(
            question,
            onConfirm = {
                asking = null
                question.kind?.let(viewModel::clear)
            },
            onDismiss = { asking = null },
        )
    }
}
