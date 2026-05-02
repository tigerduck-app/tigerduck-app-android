package org.ntust.app.tigerduck.ui.screen.settings

import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

private data class RepoEntry(
    val repoSlug: String,
    val descriptionRes: Int,
    val url: String,
    val isCurrent: Boolean = false,
)

private val ORG_ENTRY = RepoEntry(
    repoSlug = "tigerduck-app",
    descriptionRes = R.string.source_code_picker_org_description,
    url = "https://github.com/tigerduck-app",
)

private val REPO_ENTRIES = listOf(
    RepoEntry(
        repoSlug = "tigerduck-app-android",
        descriptionRes = R.string.source_code_picker_repo_android_description,
        url = "https://github.com/tigerduck-app/tigerduck-app-android",
        isCurrent = true,
    ),
    RepoEntry(
        repoSlug = "tigerduck-app",
        descriptionRes = R.string.source_code_picker_repo_apple_description,
        url = "https://github.com/tigerduck-app/tigerduck-app",
    ),
    RepoEntry(
        repoSlug = "app-translation",
        descriptionRes = R.string.source_code_picker_repo_translation_description,
        url = "https://github.com/tigerduck-app/app-translation",
    ),
    RepoEntry(
        repoSlug = "name-abbr",
        descriptionRes = R.string.source_code_picker_repo_name_abbr_description,
        url = "https://github.com/tigerduck-app/name-abbr",
    ),
    RepoEntry(
        repoSlug = "tigerduck-web",
        descriptionRes = R.string.source_code_picker_repo_web_description,
        url = "https://github.com/tigerduck-app/tigerduck-web",
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceCodePickerScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val browserPreference = viewModel.appState.browserPreference

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_view_source_code)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp),
        ) {
            item {
                ContentCard {
                    RepoLinkRow(
                        entry = ORG_ENTRY,
                        onClick = { openUrl(context, ORG_ENTRY.url, browserPreference) },
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.source_code_picker_section_repositories)) }

            item {
                ContentCard {
                    Column {
                        REPO_ENTRIES.forEachIndexed { index, entry ->
                            RepoLinkRow(
                                entry = entry,
                                onClick = { openUrl(context, entry.url, browserPreference) },
                            )
                            if (index < REPO_ENTRIES.lastIndex) {
                                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RepoLinkRow(entry: RepoEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.repoSlug,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.isCurrent) {
                    Spacer(Modifier.width(8.dp))
                    CurrentAppBadge()
                }
            }
            Text(
                text = stringResource(entry.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                maxLines = 1,
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

@Composable
private fun CurrentAppBadge() {
    Text(
        text = stringResource(R.string.source_code_picker_current_app_badge),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun openUrl(context: Context, url: String, browserPreference: String) {
    val uri = url.toUri()
    if (browserPreference == "inApp") {
        CustomTabsIntent.Builder().build().launchUrl(context, uri)
    } else {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
}
