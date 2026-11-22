package org.ntust.app.tigerduck.ui.screen.mail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.util.PageZoom

/**
 * The text size row at the top of the message's ⋮, as the iPhone's (tigerduck-app#1047): smaller,
 * the percentage, which puts it back to 100%, and larger, in [PageZoom]'s steps. None of them
 * closes the menu, so the mail behind it shows each size as it is picked.
 */
@Composable
internal fun MailTextSizeRow(percent: Int, onChange: (Int) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val label = remember(percent, locale) { PageZoom.label(percent, locale) }
    val reset = stringResource(R.string.school_mail_text_size_reset)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onChange(PageZoom.zoomOut(percent)) }, enabled = PageZoom.canZoomOut(percent)) {
            Icon(Icons.Filled.TextDecrease, contentDescription = stringResource(R.string.school_mail_text_smaller))
        }
        // Read as what a tap does, then the size it is at.
        TextButton(
            onClick = { onChange(PageZoom.DEFAULT) },
            modifier = Modifier.semantics {
                contentDescription = reset
                stateDescription = label
            },
        ) {
            // No narrower than 48dp, so the row stays put from 50% to 200%.
            Text(label, modifier = Modifier.widthIn(min = 48.dp), maxLines = 1, textAlign = TextAlign.Center)
        }
        IconButton(onClick = { onChange(PageZoom.zoomIn(percent)) }, enabled = PageZoom.canZoomIn(percent)) {
            Icon(Icons.Filled.TextIncrease, contentDescription = stringResource(R.string.school_mail_text_larger))
        }
    }
    HorizontalDivider()
}
