package org.ntust.app.tigerduck.ui.screen.announcements

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mikepenz.markdown.m3.Markdown
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.network.model.BulletinSummary
import org.ntust.app.tigerduck.network.model.TaxonomyResponse
import org.ntust.app.tigerduck.network.model.localizedTagLabel
import org.ntust.app.tigerduck.network.model.orgLabel
import org.ntust.app.tigerduck.ui.component.NoTopBarInsets
import org.ntust.app.tigerduck.ui.component.scrollbar
import org.ntust.app.tigerduck.ui.openLink

@OptIn(
    ExperimentalLayoutApi::class,
    ExperimentalMaterial3Api::class,
)
@Composable
fun AnnouncementDetailScreen(
    browserPreference: String,
    onBack: () -> Unit,
    viewModel: AnnouncementDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val sourceUrl: String? = when (val s = state) {
        is AnnouncementDetailViewModel.State.Loaded -> s.detail.sourceUrl
        is AnnouncementDetailViewModel.State.Partial -> s.summary.sourceUrl
        is AnnouncementDetailViewModel.State.Failed -> s.summary?.sourceUrl
        is AnnouncementDetailViewModel.State.Loading -> null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = NoTopBarInsets,
                title = {},
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
        floatingActionButton = {
            if (!sourceUrl.isNullOrEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { openBulletinSource(context, sourceUrl, browserPreference) },
                    icon = {
                        Icon(Icons.Filled.OpenInBrowser, contentDescription = null)
                    },
                    text = { Text(stringResource(R.string.bulletin_source_link_label)) },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val s = state) {
                is AnnouncementDetailViewModel.State.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                is AnnouncementDetailViewModel.State.Failed -> {
                    if (s.summary != null) {
                        DetailBody(
                            chrome = s.summary,
                            body = s.summary.summary,
                            bodyState = BodyState.Failed(onRetry = viewModel::reload),
                            taxonomy = s.taxonomy,
                            browserPreference = browserPreference,
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(stringResource(R.string.bulletin_body_load_failed))
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = viewModel::reload) {
                                Text(stringResource(R.string.action_retry))
                            }
                        }
                    }
                }

                is AnnouncementDetailViewModel.State.Partial -> DetailBody(
                    chrome = s.summary,
                    body = null,
                    bodyState = BodyState.Loading,
                    taxonomy = s.taxonomy,
                    browserPreference = browserPreference,
                )

                is AnnouncementDetailViewModel.State.Loaded -> DetailBody(
                    chrome = s.detail.toSummary(),
                    body = BulletinBodyRenderer.bodyMarkdown(s.detail, viewModel.listSummary),
                    bodyState = BodyState.Ready,
                    taxonomy = s.taxonomy,
                    browserPreference = browserPreference,
                )
            }
        }
    }
}

private sealed interface BodyState {
    data object Ready : BodyState
    data object Loading : BodyState
    data class Failed(val onRetry: () -> Unit) : BodyState
}

/**
 * [body] is the markdown to show under the title: the body once it has loaded, or the summary
 * under the error when it could not.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailBody(
    chrome: BulletinSummary,
    body: String?,
    bodyState: BodyState,
    taxonomy: TaxonomyResponse?,
    browserPreference: String,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .scrollbar(scrollState)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Meta row: 處室 reads as attribution (accent-color caption), kept
        // visually separate from the 類別 hashtag strip in the footer.
        Row(verticalAlignment = Alignment.CenterVertically) {
            chrome.canonicalOrg?.let { orgId ->
                Text(
                    text = taxonomy?.orgLabel(orgId) ?: orgId,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
            }
            if (chrome.importance == "high") {
                Text(
                    text = stringResource(R.string.bulletin_importance_high_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = androidx.compose.ui.graphics.Color(0xFFFF9500),
                )
                Spacer(Modifier.width(8.dp))
            }
            if (chrome.isDeleted) {
                Text(
                    text = stringResource(R.string.bulletin_withdrawn_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.weight(1f))
            chrome.postedAt?.let {
                Text(
                    text = it.substringBefore('T'),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        Text(
            text = chrome.displayTitle,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        )
        // Body content stays in Mandarin (server-side, original language).
        // Which field it comes from is BulletinBodyRenderer.bodyMarkdown's call.
        when (bodyState) {
            BodyState.Ready -> body?.let { BodyMarkdown(it, browserPreference) }

            BodyState.Loading -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }

            is BodyState.Failed -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.bulletin_body_load_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = bodyState.onRetry) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
                // The summary stands in for the body it could not load, as on the iPhone.
                body?.let { BodyMarkdown(it, browserPreference) }
            }
        }
        // Footer: hashtag strip — wraps multi-line for posts with many tags.
        if (chrome.contentTags.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                chrome.contentTags.forEach { id ->
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    ) {
                        Text(
                            text = "#${taxonomy.localizedTagLabel(id, ctx)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Server markdown with [BulletinBodyRenderer.normalize]'s fix-ups applied; nothing when blank.
 *
 * A tapped link goes through [openLink], as the Source button's does: Compose's own handler would
 * start whatever app claims the link's scheme, and open a web page outside "Open links with".
 */
@Composable
internal fun BodyMarkdown(markdown: String, browserPreference: String) {
    if (markdown.isBlank()) return
    val normalized = remember(markdown) { BulletinBodyRenderer.normalize(markdown) }
    val context = LocalContext.current
    val handler = remember(context, browserPreference) {
        object : UriHandler {
            override fun openUri(uri: String) {
                openLink(context, uri, browserPreference)
            }
        }
    }
    CompositionLocalProvider(LocalUriHandler provides handler) {
        Markdown(content = normalized)
    }
}

/**
 * Opens a bulletin's source page through [openLink], the way the link-opening setting says: in an
 * in-app browser tab, or in the system's default browser.
 *
 * Only a web page: the address comes from the bulletin feed, and [openLink] would also hand a
 * `mailto:` link to a mail app -- handed to the system as it is, any other scheme could start
 * whatever app claims it.
 */
internal fun openBulletinSource(context: Context, url: String, browserPreference: String) {
    if (url.toUri().scheme?.lowercase() !in WEB_SCHEMES) return
    openLink(context, url, browserPreference)
}

private val WEB_SCHEMES = setOf("http", "https")
