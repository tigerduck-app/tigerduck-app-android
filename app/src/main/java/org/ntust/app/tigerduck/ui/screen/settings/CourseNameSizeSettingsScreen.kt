package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlin.math.roundToInt
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.preferences.CourseNameScale
import org.ntust.app.tigerduck.ui.component.ContentCard
import org.ntust.app.tigerduck.ui.component.SectionHeader
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.ui.theme.TigerDuckTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseNameSizeSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val persistedScale = viewModel.appState.courseNameScale
    // Drag-local mirror so onValueChange can drive the preview at 60fps
    // without firing a SharedPreferences write + widget rebuild on every
    // frame. The persisted value is only updated on onValueChangeFinished
    // (release) and on the reset row. Reset persistedScale when the source
    // of truth changes from elsewhere (e.g. reset row, full-reset flow).
    var dragScale by remember(persistedScale) { mutableFloatStateOf(persistedScale) }
    val scale = dragScale
    // Compose Slider's `steps` is the count of discrete values *between*
    // the two endpoints. For 0.8…1.6 at 0.05 we have 17 stops total →
    // 15 in between.
    val stepCount = ((CourseNameScale.MAX - CourseNameScale.MIN) / CourseNameScale.STEP)
        .roundToInt() - 1

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_font_size_title)) },
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
        containerColor = MaterialTheme.colorScheme.background,
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        ) {
            item {
                SectionHeader(stringResource(R.string.settings_font_size_preview_section))
            }
            item {
                ContentCard {
                    Column(modifier = Modifier.padding(12.dp)) {
                        PreviewRow(scale = scale)
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.settings_font_size_preview_footer),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                        .copy(alpha = ContentAlpha.SECONDARY),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }

            item {
                SectionHeader(stringResource(R.string.settings_font_size_picker_section))
            }
            item {
                ContentCard {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "A",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface
                                    .copy(alpha = ContentAlpha.SECONDARY),
                            )
                            Spacer(Modifier.width(8.dp))
                            Slider(
                                value = scale,
                                onValueChange = { dragScale = it },
                                onValueChangeFinished = {
                                    // Commit once on release — avoids ~17
                                    // prefs writes + widget rebuilds per
                                    // slider sweep. The AppState setter
                                    // re-normalises and dedups internally.
                                    viewModel.appState.courseNameScale = dragScale
                                },
                                valueRange = CourseNameScale.MIN..CourseNameScale.MAX,
                                steps = stepCount,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "A",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                                    .copy(alpha = ContentAlpha.SECONDARY),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = scaleLabel(scale),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                                .copy(alpha = ContentAlpha.SECONDARY),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(SettingRowHeight)
                                .clickable(
                                    enabled = scale != CourseNameScale.DEFAULT,
                                ) {
                                    dragScale = CourseNameScale.DEFAULT
                                    viewModel.appState.courseNameScale = CourseNameScale.DEFAULT
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            val isDefault = scale == CourseNameScale.DEFAULT
                            Text(
                                stringResource(R.string.settings_font_size_reset_button),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                                    .copy(alpha = if (isDefault) ContentAlpha.DISABLED else 1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Three mock cards approximating the timetable's solo cell + 2-course
 * conflict cluster from the iOS preview. Uses inline colors so the
 * preview doesn't depend on having a real `Course` to render.
 */
@Composable
private fun PreviewRow(scale: Float) {
    val cellHeight = 52.dp
    val rowSpacing = 3.dp
    val cornerRadius = 8.dp
    val baseFontSize = 11.sp
    val scaledFontSize = baseFontSize * scale

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(rowSpacing),
    ) {
        PreviewCell(
            name = stringResource(R.string.settings_font_size_preview_course_name),
            color = MaterialTheme.colorScheme.primary,
            fontSize = scaledFontSize,
            modifier = Modifier
                .weight(1f)
                .height(cellHeight),
            cornerRadius = cornerRadius,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(rowSpacing),
        ) {
            PreviewCell(
                name = stringResource(R.string.settings_font_size_preview_course_name_alt_1),
                color = Color(0xFF34C759),
                fontSize = scaledFontSize,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cellHeight),
                cornerRadius = cornerRadius,
            )
            PreviewCell(
                name = stringResource(R.string.settings_font_size_preview_course_name_alt_2),
                color = Color(0xFFFF9500),
                fontSize = scaledFontSize,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cellHeight),
                cornerRadius = cornerRadius,
            )
        }
    }
}

@Composable
private fun PreviewCell(
    name: String,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp,
) {
    val surface = MaterialTheme.colorScheme.surface
    val lightAlpha = 0.50f
    val darkAlpha = 0.55f
    val cellColor = if (TigerDuckTheme.isDarkMode) {
        color.copy(alpha = darkAlpha).compositeOver(surface)
    } else {
        color.copy(alpha = lightAlpha)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(cellColor)
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** Format `1.20×` with two decimals so the trailing-zero digit doesn't shift the column. */
internal fun scaleLabel(scale: Float): String =
    "%.2f×".format(CourseNameScale.normalize(scale))
