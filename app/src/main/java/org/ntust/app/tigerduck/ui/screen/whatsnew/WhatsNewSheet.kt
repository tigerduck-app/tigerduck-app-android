package org.ntust.app.tigerduck.ui.screen.whatsnew

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.WhatsNewSummary
import org.ntust.app.tigerduck.data.model.WhatsNewSummaryItem

/**
 * The paged What's New sheet: [WhatsNewFlow.pages] one at a time, then the
 * summary page. Swiping the sheet down, the system back gesture or a tap on
 * the scrim closes the whole flow from any page; [onDismiss] runs either way,
 * so the caller records the flow as seen in one place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewSheet(flow: WhatsNewFlow, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var finishing by remember { mutableStateOf(false) }
    // Read here, in the activity's composition, from the same app-locale-aware
    // configuration the summary was looked up with — so page copy and the
    // summary always agree on the language.
    val language = WhatsNewLanguage.of(LocalConfiguration.current.locales[0].toLanguageTag())
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        CompositionLocalProvider(LocalWhatsNewLanguage provides language) {
            WhatsNewFlowContent(
                flow = flow,
                onFinish = {
                    // Animate the sheet away before dropping it; guarded so a
                    // double tap on the last button can't dismiss twice.
                    if (!finishing) {
                        finishing = true
                        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                    }
                },
            )
        }
    }
}

@Composable
private fun WhatsNewFlowContent(flow: WhatsNewFlow, onFinish: () -> Unit) {
    val context = LocalContext.current
    // A Binder call, so read once per sheet rather than per recomposition.
    val animate = remember(context) { animationsEnabled(context) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val lastIndex = flow.stepCount - 1
    var index by rememberSaveable { mutableIntStateOf(0) }
    val current = index.coerceIn(0, lastIndex)
    val advance: () -> Unit = { if (current >= lastIndex) onFinish() else index = current + 1 }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // A fixed height so the sheet doesn't jump between short and
            // tall pages.
            .fillMaxHeight(0.92f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 4.dp),
        ) {
            if (current > 0) {
                IconButton(
                    onClick = { index = current - 1 },
                    modifier = Modifier.align(Alignment.CenterStart),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            }
            if (flow.stepCount > 1) {
                val language = LocalWhatsNewLanguage.current
                PageDots(
                    count = flow.stepCount,
                    current = current,
                    description = pagePositionDescription(
                        step = current,
                        count = flow.stepCount,
                        title = stepTitle(flow, current, language),
                        language = language,
                    ),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        AnimatedContent(
            targetState = current,
            transitionSpec = {
                val transform = if (!animate) {
                    fadeIn(tween(150)) togetherWith fadeOut(tween(150))
                } else {
                    // Forward slides in from the trailing edge; flip for RTL.
                    val forward = targetState > initialState
                    val sign = (if (forward) 1 else -1) * (if (rtl) -1 else 1)
                    (slideInHorizontally { width -> sign * width } + fadeIn()) togetherWith
                        (slideOutHorizontally { width -> -sign * width } + fadeOut())
                }
                transform.using(SizeTransform(clip = false))
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            label = "whatsNewStep",
        ) { step ->
            val isLast = step == lastIndex
            if (step < flow.pages.size) {
                PageStep(page = flow.pages[step], isLast = isLast, animate = animate, advance = advance)
            } else {
                flow.summary?.let { SummaryStep(summary = it, onContinue = advance) }
            }
        }
    }
}

// --- Feature pages ---

@Composable
private fun PageStep(page: WhatsNewPage, isLast: Boolean, animate: Boolean, advance: () -> Unit) {
    val primaryLabel = stringResource(if (isLast) R.string.whats_new_continue else R.string.action_next)
    Column(modifier = Modifier.fillMaxSize()) {
        when (page) {
            is WhatsNewPage.Feature -> {
                PageBody(page.visual, page.title, page.body, animate)
                PrimaryButton(primaryLabel, advance)
            }

            is WhatsNewPage.OptIn -> {
                PageBody(page.visual, page.title, page.body, animate)
                ButtonArea {
                    Button(
                        onClick = {
                            page.apply()
                            advance()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(page.confirmLabel.resolve()) }
                    TextButton(onClick = advance, modifier = Modifier.fillMaxWidth()) {
                        Text(page.declineLabel.resolve())
                    }
                }
            }

            is WhatsNewPage.Permission -> {
                PageBody(page.visual, page.title, page.body, animate)
                PermissionButtons(page, advance)
            }

            is WhatsNewPage.Choice -> {
                PageBody(page.visual, page.title, page.body, animate) {
                    Spacer(Modifier.height(24.dp))
                    ChoiceCards(page)
                }
                PrimaryButton(primaryLabel, advance)
            }

            is WhatsNewPage.Toggle -> {
                PageBody(page.visual, page.title, page.body, animate) {
                    Spacer(Modifier.height(24.dp))
                    ToggleRow(page)
                }
                PrimaryButton(primaryLabel, advance)
            }

            is WhatsNewPage.Custom -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    page.content(
                        WhatsNewPageContext(
                            advance = advance,
                            animate = animate,
                            language = LocalWhatsNewLanguage.current,
                        ),
                    )
                }
                if (page.showsNextButton) PrimaryButton(primaryLabel, advance)
            }
        }
    }
}

@Composable
private fun ColumnScope.PageBody(
    visual: WhatsNewVisual?,
    title: WhatsNewText,
    body: WhatsNewText,
    animate: Boolean,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        if (visual != null) {
            VisualView(visual, animate)
            Spacer(Modifier.height(28.dp))
        }
        Text(
            title.resolve(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            body.resolve(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        extra()
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun VisualView(visual: WhatsNewVisual, animate: Boolean) {
    when (visual) {
        is WhatsNewVisual.Icon -> Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                visual.image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(64.dp)
                    .whatsNewEffect(if (animate) visual.effect else WhatsNewEffect.None),
            )
        }

        is WhatsNewVisual.Custom -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp),
            contentAlignment = Alignment.Center,
        ) {
            visual.content(animate)
        }
    }
}

/**
 * The looping icon animation. Each effect idles between beats so a page
 * left open doesn't keep the icon in constant motion.
 */
@Composable
private fun Modifier.whatsNewEffect(effect: WhatsNewEffect): Modifier {
    if (effect == WhatsNewEffect.None) return this
    val transition = rememberInfiniteTransition(label = "whatsNewEffect")
    return when (effect) {
        WhatsNewEffect.None -> this

        WhatsNewEffect.Bounce -> {
            val offsetDp by transition.animateFloat(
                initialValue = 0f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    keyframes {
                        durationMillis = 1800
                        0f at 0 using FastOutSlowInEasing
                        -14f at 200 using FastOutSlowInEasing
                        0f at 450
                        0f at 1800
                    },
                ),
                label = "bounce",
            )
            graphicsLayer { translationY = offsetDp * density }
        }

        WhatsNewEffect.Pulse -> {
            val alpha by transition.animateFloat(
                initialValue = 1f,
                targetValue = 0.4f,
                animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
                label = "pulse",
            )
            graphicsLayer { this.alpha = alpha }
        }

        WhatsNewEffect.Wiggle -> {
            val angle by transition.animateFloat(
                initialValue = 0f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    keyframes {
                        durationMillis = 1800
                        0f at 0
                        -12f at 100
                        12f at 250
                        -8f at 400
                        8f at 550
                        0f at 700
                        0f at 1800
                    },
                ),
                label = "wiggle",
            )
            graphicsLayer { rotationZ = angle }
        }

        WhatsNewEffect.Breathe -> {
            val scale by transition.animateFloat(
                initialValue = 1f,
                targetValue = 1.1f,
                animationSpec = infiniteRepeatable(
                    tween(1400, easing = FastOutSlowInEasing),
                    RepeatMode.Reverse,
                ),
                label = "breathe",
            )
            graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
        }

        WhatsNewEffect.Rotate -> {
            val angle by transition.animateFloat(
                initialValue = 0f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    keyframes {
                        durationMillis = 2400
                        0f at 0 using FastOutSlowInEasing
                        360f at 900
                        360f at 2400
                    },
                ),
                label = "rotate",
            )
            graphicsLayer { rotationZ = angle }
        }
    }
}

@Composable
private fun ChoiceCards(page: WhatsNewPage.Choice) {
    var selectedId by rememberSaveable(page.id) { mutableStateOf(page.current()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        page.options.forEach { option ->
            val selected = option.id == selectedId
            Surface(
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                ),
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = {
                            if (!selected) {
                                selectedId = option.id
                                page.select(option.id)
                            }
                        },
                    ),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    option.preview?.let { preview ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center,
                        ) { preview() }
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        option.label.resolve(),
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Icon(
                        if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(page: WhatsNewPage.Toggle) {
    var checked by rememberSaveable(page.id) { mutableStateOf(page.get()) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    onValueChange = {
                        checked = it
                        page.set(it)
                    },
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                page.label.resolve(),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

@Composable
private fun PermissionButtons(page: WhatsNewPage.Permission, advance: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        page.onResult(results.isNotEmpty() && results.values.all { it })
        advance()
    }
    ButtonArea {
        Button(
            onClick = {
                val needed = page.permissions.filter {
                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                }
                if (Build.VERSION.SDK_INT < page.minSdk || needed.isEmpty()) {
                    // Granted at install below minSdk, or already granted.
                    page.onResult(true)
                    advance()
                } else {
                    launcher.launch(needed.toTypedArray())
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(page.confirmLabel.resolve()) }
        TextButton(onClick = advance, modifier = Modifier.fillMaxWidth()) {
            Text(page.declineLabel.resolve())
        }
    }
}

// --- Summary page ---

@Composable
private fun SummaryStep(summary: WhatsNewSummary, onContinue: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                summary.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(28.dp))
            summary.items.forEach { item ->
                SummaryRow(item)
                Spacer(Modifier.height(20.dp))
            }
        }
        PrimaryButton(stringResource(R.string.whats_new_continue), onContinue)
    }
}

@Composable
private fun SummaryRow(item: WhatsNewSummaryItem) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (item.icon == null && item.title == null) {
                // A legacy plain-text highlight: a dot reads better than the
                // same fallback glyph repeated down the list.
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            } else {
                Icon(
                    WhatsNewIcons.named(item.icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item.title?.let {
                Text(it, style = MaterialTheme.typography.titleMedium)
            }
            item.body?.let {
                Text(
                    it,
                    style = if (item.title != null) {
                        MaterialTheme.typography.bodyMedium
                    } else {
                        MaterialTheme.typography.bodyLarge
                    },
                    color = if (item.title != null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

// --- Shared pieces ---

/**
 * The dots stand in for the page position with TalkBack: one node reading
 * [description] ("Page 2 of 4: <title>"). It stays put while pages slide
 * under it, so as a polite live region its description changing is what
 * announces each new page — focus otherwise stays on the Next button, which
 * sits in the same place on every page.
 */
@Composable
private fun PageDots(count: Int, current: Int, description: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = description
            liveRegion = LiveRegionMode.Polite
        },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            Box(
                Modifier
                    .size(if (i == current) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(
                        if (i == current) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                        },
                    ),
            )
        }
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    ButtonArea {
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
    }
}

@Composable
private fun ButtonArea(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

/**
 * The page position TalkBack reads, in What's New's own two languages rather
 * than through `app-translation`, followed by the step's heading when it has
 * one (a custom page draws its own).
 */
internal fun pagePositionDescription(
    step: Int,
    count: Int,
    title: String?,
    language: WhatsNewLanguage,
): String {
    val position = WhatsNewText(
        en = "Page ${step + 1} of $count",
        zhHant = "第 ${step + 1} 頁，共 $count 頁",
    ).resolve(language)
    if (title == null) return position
    return when (language) {
        WhatsNewLanguage.ZhHant -> "$position：$title"
        WhatsNewLanguage.En -> "$position: $title"
    }
}

/** The heading of step [step]: a page's title, or the summary's. */
private fun stepTitle(flow: WhatsNewFlow, step: Int, language: WhatsNewLanguage): String? {
    val page = flow.pages.getOrNull(step) ?: return flow.summary?.title
    val title = when (page) {
        is WhatsNewPage.Feature -> page.title
        is WhatsNewPage.OptIn -> page.title
        is WhatsNewPage.Permission -> page.title
        is WhatsNewPage.Choice -> page.title
        is WhatsNewPage.Toggle -> page.title
        is WhatsNewPage.Custom -> null
    }
    return title?.resolve(language)
}

/**
 * False when the system "Remove animations" / animator duration scale is 0:
 * the icon effects stop and page changes fade instead of sliding.
 */
private fun animationsEnabled(context: Context): Boolean =
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) != 0f
