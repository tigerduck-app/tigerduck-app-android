package org.ntust.app.tigerduck.ui.screen.mail

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import org.ntust.app.tigerduck.mail.sanitize.PlainTextLink
import org.ntust.app.tigerduck.util.PageZoom

/**
 * A plain-text mail with its URLs and addresses drawn as links. A tap only reports the link's
 * index: what it opens goes through the same check dialog an HTML link does. Kept selectable --
 * the caller wraps it in a SelectionContainer, and a long press still selects across links.
 * Drawn at the mail's text size, [textZoom] percent of the body text's, as the formatted view.
 */
@Composable
internal fun LinkedPlainText(
    text: String,
    links: List<PlainTextLink>,
    onLink: (Int) -> Unit,
    modifier: Modifier = Modifier,
    textZoom: Int = PageZoom.DEFAULT,
) {
    val linkStyle = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    val latestOnLink by rememberUpdatedState(onLink)
    val annotated = remember(text, links, linkStyle) {
        buildAnnotatedString {
            append(text)
            links.forEachIndexed { index, link ->
                addLink(LinkAnnotation.Clickable(tag = "link-$index", styles = linkStyle) { latestOnLink(index) }, link.start, link.end)
            }
        }
    }
    val body = MaterialTheme.typography.bodyMedium
    val scale = textZoom / 100f
    Text(annotated, style = body.copy(fontSize = body.fontSize * scale, lineHeight = body.lineHeight * scale), modifier = modifier)
}
