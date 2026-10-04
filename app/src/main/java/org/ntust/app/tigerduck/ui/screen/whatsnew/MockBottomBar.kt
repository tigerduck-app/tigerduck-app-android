package org.ntust.app.tigerduck.ui.screen.whatsnew

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.ui.navigation.icon

private val yoursCaption = WhatsNewText(en = "Your bottom bar", zhHant = "目前的底部功能列")
private val beforeCaption = WhatsNewText(en = "Before", zhHant = "調整前")
private val afterCaption = WhatsNewText(en = "After", zhHant = "調整後")

/**
 * The demo for a page that offers to change the bottom bar: [before] above
 * [after], both on screen at once. Tabs leaving are drawn selected in Before,
 * tabs arriving in After. When the two match — a replay on a bar that
 * already is the offered one, or Back after confirming — just the one bar
 * shows. TalkBack reads it as one node listing both bars
 * ([spokenBarChange]), so the tab a change would remove is heard, not just
 * seen.
 */
@Composable
internal fun BottomBarChangeDemo(before: List<AppFeature>, after: List<AppFeature>) {
    val beforeNames = (before + AppFeature.MORE).map { stringResource(it.shortDisplayNameRes) }
    val afterNames = (after + AppFeature.MORE).map { stringResource(it.shortDisplayNameRes) }
    val description = spokenBarChange(beforeNames, afterNames, LocalWhatsNewLanguage.current)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (before == after) {
            BarCaption(yoursCaption)
            MockBottomBar(before, highlighted = emptySet())
        } else {
            BarCaption(beforeCaption)
            MockBottomBar(before, highlighted = before.toSet() - after.toSet())
            Icon(
                Icons.Filled.ArrowDownward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            BarCaption(afterCaption)
            MockBottomBar(after, highlighted = after.toSet() - before.toSet())
        }
    }
}

/**
 * "Before: Home, Class table, Calendar, More. After: Home, Class table, Mail,
 * More." from the bars' tab names, More included — or just the one bar when
 * nothing changes.
 */
internal fun spokenBarChange(
    before: List<String>,
    after: List<String>,
    language: WhatsNewLanguage,
): String {
    fun spoken(caption: WhatsNewText, names: List<String>) = when (language) {
        WhatsNewLanguage.ZhHant -> caption.resolve(language) + "：" + names.joinToString("、")
        WhatsNewLanguage.En -> caption.resolve(language) + ": " + names.joinToString(", ")
    }
    if (before == after) return spoken(yoursCaption, before)
    val sentences = listOf(spoken(beforeCaption, before), spoken(afterCaption, after))
    return when (language) {
        WhatsNewLanguage.ZhHant -> sentences.joinToString("。") + "。"
        WhatsNewLanguage.En -> sentences.joinToString(". ") + "."
    }
}

@Composable
private fun BarCaption(text: WhatsNewText) {
    Text(
        text.resolve(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * One row of tab items styled like the app's NavigationBar: [tabs] then
 * MORE, with the [highlighted] ones drawn as selected.
 */
@Composable
private fun MockBottomBar(tabs: List<AppFeature>, highlighted: Set<AppFeature>) {
    Row(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        (tabs + AppFeature.MORE).forEach { feature ->
            val selected = feature in highlighted
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (selected) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer,
                        )
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                ) {
                    Icon(
                        feature.icon,
                        contentDescription = null,
                        tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Text(
                    stringResource(feature.shortDisplayNameRes),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
