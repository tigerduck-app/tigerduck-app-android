package org.ntust.app.tigerduck.announcements

import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mikepenz.markdown.m3.Markdown
import org.ntust.app.tigerduck.R

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AnnouncementDetailScreen(
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
        floatingActionButton = {
            if (!sourceUrl.isNullOrEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = {
                        try {
                            CustomTabsIntent.Builder().build().launchUrl(context, sourceUrl.toUri())
                        } catch (_: Exception) {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, sourceUrl.toUri())
                                )
                            }
                        }
                    },
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
                .padding(padding)
                .statusBarsPadding(),
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
                            bodyMd = null,
                            bodyClean = null,
                            bodyState = BodyState.Failed(onRetry = viewModel::reload),
                            taxonomy = s.taxonomy,
                        )
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(24.dp),
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
                    bodyMd = null,
                    bodyClean = null,
                    bodyState = BodyState.Loading,
                    taxonomy = s.taxonomy,
                )
                is AnnouncementDetailViewModel.State.Loaded -> DetailBody(
                    chrome = s.detail.toSummary(),
                    bodyMd = s.detail.bodyMd,
                    bodyClean = s.detail.bodyClean,
                    bodyState = BodyState.Ready,
                    taxonomy = s.taxonomy,
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DetailBody(
    chrome: BulletinSummary,
    bodyMd: String?,
    bodyClean: String?,
    bodyState: BodyState,
    taxonomy: TaxonomyResponse?,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Meta row: 處室 reads as attribution (accent-color caption), kept
        // visually separate from the 類別 hashtag strip in the footer.
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
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
        // Render the markdown source when available so headings, lists, and
        // links carry their formatting; fall back to the cleaned plain text.
        when (bodyState) {
            BodyState.Ready -> {
                val md = bodyMd?.takeIf { it.isNotBlank() }
                val plain = bodyClean?.takeIf { it.isNotBlank() }
                when {
                    md != null -> Markdown(content = md)
                    plain != null -> Text(
                        text = plain,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            BodyState.Loading -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
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
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
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
            }
        }
        // Footer: hashtag strip — wraps multi-line for posts with many tags.
        if (chrome.contentTags.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                chrome.contentTags.forEach { id ->
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    ) {
                        Text(
                            text = "#${taxonomy?.tagLabel(id) ?: id}",
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
