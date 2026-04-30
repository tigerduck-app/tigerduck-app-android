package org.ntust.app.tigerduck.announcements

import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import org.ntust.app.tigerduck.R

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AnnouncementDetailScreen(
    onBack: () -> Unit,
    viewModel: AnnouncementDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = (state as? AnnouncementDetailViewModel.State.Loaded)
                            ?.detail?.displayTitle ?: "",
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    val url = (state as? AnnouncementDetailViewModel.State.Loaded)
                        ?.detail?.sourceUrl
                    if (!url.isNullOrEmpty()) {
                        IconButton(onClick = {
                            try {
                                CustomTabsIntent.Builder().build().launchUrl(context, url.toUri())
                            } catch (_: Exception) {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, url.toUri())
                                    )
                                }
                            }
                        }) {
                            Icon(
                                Icons.Filled.OpenInBrowser,
                                contentDescription = stringResource(R.string.bulletin_source_link_label),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                AnnouncementDetailViewModel.State.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is AnnouncementDetailViewModel.State.Failed -> {
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
                is AnnouncementDetailViewModel.State.Loaded -> DetailBody(s.detail, s.taxonomy)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DetailBody(
    detail: BulletinDetail,
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
            detail.canonicalOrg?.let { orgId ->
                Text(
                    text = taxonomy?.orgLabel(orgId) ?: orgId,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
            }
            if (detail.importance == "high") {
                Text(
                    text = stringResource(R.string.bulletin_importance_high_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = androidx.compose.ui.graphics.Color(0xFFFF9500),
                )
                Spacer(Modifier.width(8.dp))
            }
            if (detail.isDeleted) {
                Text(
                    text = stringResource(R.string.bulletin_withdrawn_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.weight(1f))
            detail.postedAt?.let {
                Text(
                    text = it.substringBefore('T'),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        Text(
            text = detail.displayTitle,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        )
        // Body content stays in Mandarin (server-side, original language).
        // Prefer the cleaned plain text; fall back to markdown source if that
        // is the only thing the LLM produced for this row.
        val body = detail.bodyClean ?: detail.bodyMd
        if (!body.isNullOrBlank()) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        // Footer: hashtag strip — wraps multi-line for posts with many tags.
        if (detail.contentTags.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                detail.contentTags.forEach { id ->
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
