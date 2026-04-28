package org.ntust.app.tigerduck.ui.screen.settings

import android.content.Context
import org.xmlpull.v1.XmlPullParser
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import java.text.Collator
import java.util.Locale


private fun supportedLanguageTagsFromLocaleConfig(context: Context): List<String> {
    val parser = context.resources.getXml(R.xml.locales_config)
    val tags = mutableListOf<String>()
    while (true) {
        when (parser.next()) {
            XmlPullParser.END_DOCUMENT -> return tags
            XmlPullParser.START_TAG -> {
                if (parser.name == "locale") {
                    val tag = parser.getAttributeValue(
                        "http://schemas.android.com/apk/res/android",
                        "name",
                    )
                    if (!tag.isNullOrBlank()) tags.add(tag)
                }
            }
        }
    }
}

private data class LanguageRow(
    val tag: String,
    val nativeName: String,
    val localizedName: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagePickerScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val selectedTag = viewModel.appState.appLanguage

    var query by rememberSaveable { mutableStateOf("") }

    val currentLocale = remember {
        context.resources.configuration.locales[0] ?: Locale.getDefault()
    }

    val supportedTags = remember {
        supportedLanguageTagsFromLocaleConfig(context)
    }

    val allRows = remember(currentLocale, supportedTags) {
        val collator = Collator.getInstance(Locale.ROOT)
        val rows = supportedTags
            .map { tag ->
                val locale = Locale.forLanguageTag(tag)
                LanguageRow(
                    tag = tag,
                    nativeName = locale.getDisplayName(locale).ifBlank { tag },
                    localizedName = locale.getDisplayName(currentLocale).ifBlank { tag },
                )
            }
            .sortedWith { a, b -> collator.compare(a.nativeName, b.nativeName) }
        rows
    }

    val filteredRows by remember(query, allRows) {
        derivedStateOf {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) {
                allRows
            } else {
                val q = trimmed.lowercase(Locale.ROOT)
                allRows.filter { row ->
                    row.tag.lowercase(Locale.ROOT).contains(q) ||
                        row.nativeName.lowercase(Locale.ROOT).contains(q) ||
                        row.localizedName.lowercase(Locale.ROOT).contains(q)
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_language)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text(stringResource(R.string.action_search)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                item {
                    LanguagePickerRow(
                        title = stringResource(R.string.settings_language_follow_system),
                        subtitle = null,
                        selected = selectedTag == AppLanguageManager.SYSTEM,
                        onClick = {
                            coroutineScope.launch {
                                // Delay slightly so the tap ripple and list
                                // state settle before the configuration
                                // change kicks in.
                                delay(120)
                                viewModel.setAppLanguage(AppLanguageManager.SYSTEM)
                                onBack()
                            }
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                }

                items(filteredRows, key = { it.tag }) { row ->
                    LanguagePickerRow(
                        title = row.nativeName,
                        subtitle = if (row.nativeName != row.localizedName) row.localizedName else null,
                        selected = selectedTag == row.tag,
                        onClick = {
                            coroutineScope.launch {
                                delay(120)
                                viewModel.setAppLanguage(row.tag)
                                onBack()
                            }
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                }
            }
        }
    }
}

@Composable
private fun LanguagePickerRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

