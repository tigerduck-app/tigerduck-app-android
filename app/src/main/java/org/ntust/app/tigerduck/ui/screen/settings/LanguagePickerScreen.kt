package org.ntust.app.tigerduck.ui.screen.settings

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.xmlpull.v1.XmlPullParser
import java.text.Collator
import java.util.Locale


private fun supportedLanguageTagsFromLocaleConfig(context: Context): List<String> {
    val parser = context.resources.getXml(R.xml.locales_config)
    val tags = mutableListOf<String>()
    parser.use {
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
    val configuration = LocalConfiguration.current
    val coroutineScope = rememberCoroutineScope()
    val selectedTag = viewModel.appState.appLanguage

    var query by rememberSaveable { mutableStateOf("") }

    val currentLocale = remember(configuration) {
        configuration.locales[0] ?: Locale.getDefault()
    }

    var supportedTags by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(Unit) {
        supportedTags = withContext(Dispatchers.IO) {
            supportedLanguageTagsFromLocaleConfig(context)
        }
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

    val filteredRows = remember(query, allRows) {
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

    val scrollState = rememberScrollState()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
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
                .verticalScroll(scrollState)
                .scrollbar(scrollState)
                .padding(top = 4.dp, bottom = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.language_picker_ai_translation_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            SearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.action_search),
            )

            ContentCard {
                Column {
                    LanguagePickerRow(
                        title = stringResource(R.string.settings_language_follow_system),
                        subtitle = null,
                        selected = selectedTag == AppLanguageManager.SYSTEM,
                        onClick = {
                            coroutineScope.launch {
                                delay(120)
                                viewModel.setAppLanguage(AppLanguageManager.SYSTEM)
                                onBack()
                            }
                        }
                    )
                }
            }

            if (filteredRows.isNotEmpty()) {
                ContentCard {
                    Column {
                        filteredRows.forEachIndexed { index, row ->
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
                            if (index < filteredRows.lastIndex) {
                                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Modifier.scrollbar(
    state: ScrollState,
    width: Dp = 3.dp,
    inset: Dp = 2.dp,
): Modifier {
    val targetAlpha = if (state.isScrollInProgress) 1f else 0f
    val duration = if (state.isScrollInProgress) 150 else 500
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = duration),
        label = "scrollbar_alpha",
    )
    val barColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    return drawWithContent {
        drawContent()
        val maxValue = state.maxValue
        if (maxValue <= 0 || alpha <= 0f) return@drawWithContent
        val viewportH = size.height
        val totalH = viewportH + maxValue
        val thumbH = (viewportH * viewportH / totalH).coerceAtLeast(40f)
        val thumbY = (state.value.toFloat() / maxValue) * (viewportH - thumbH)
        val barWidthPx = width.toPx()
        val insetPx = inset.toPx()
        drawRoundRect(
            color = barColor.copy(alpha = barColor.alpha * alpha),
            topLeft = Offset(size.width - barWidthPx - insetPx, thumbY),
            size = Size(barWidthPx, thumbH),
            cornerRadius = CornerRadius(barWidthPx / 2f, barWidthPx / 2f),
        )
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
            if (query.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                )
            }
        }
        if (query.isNotEmpty()) {
            Icon(
                Icons.Filled.Clear,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                modifier = Modifier
                    .size(18.dp)
                    .clickable { onQueryChange("") },
            )
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
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

