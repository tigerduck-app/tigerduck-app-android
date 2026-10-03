package org.ntust.app.tigerduck.ui.screen.mail

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.mail.model.MailAddress

/** The height and width a finger is entitled to. */
private val TouchTarget = 48.dp

/** One recipient of a mail, as the header's To and Cc lines show it. */
internal data class MailRecipient(
    /** Null when the header gave none. */
    val name: String?,
    /** Null for a mailbox kept only for its name -- a bounce's `<MAILER-DAEMON>`; see [MailAddress]. */
    val address: String?,
    /** This is the signed-in student's own address. */
    val isSelf: Boolean,
) {
    /** `Name <address>`, the bare address, or the name alone when there is no address to show. */
    val displayText: String
        get() = when {
            address == null -> name.orEmpty()
            name == null -> address
            else -> "$name <$address>"
        }

    companion object {
        /**
         * Each entry of a [org.ntust.app.tigerduck.mail.model.MailSummary.to]/`cc` list as a
         * recipient. An entry with neither a name nor an address is dropped rather than left as an
         * empty line. [ownAddress] is compared case-insensitively: Mail2000 writes addresses
         * lowercase, a sender may not.
         */
        fun from(list: List<MailAddress>, ownAddress: String?): List<MailRecipient> = list.mapNotNull { entry ->
            val name = entry.name?.trim()?.takeIf { it.isNotEmpty() }
            val address = entry.address.trim().takeIf { it.isNotEmpty() }
            if (name == null && address == null) return@mapNotNull null
            MailRecipient(name, address, isSelf = ownAddress != null && address.equals(ownAddress, ignoreCase = true))
        }
    }
}

/**
 * The order the header shows recipients in: the student's own address first, so "this one is me"
 * reads at a glance in a mail sent to a whole class -- collapsed as well, where only the first is
 * shown -- and the rest as the mail lists them.
 */
internal fun headerOrder(recipients: List<MailRecipient>): List<MailRecipient> = recipients.sortedByDescending { it.isSelf }

/**
 * The shown recipients, one per line after a comma, then "+N" for the ones collapsed away. The
 * student's own address is in [accent], so "this one is me" reads at a glance in a mail sent to a
 * whole class.
 */
internal fun recipientLine(shown: List<MailRecipient>, hiddenCount: Int, accent: Color): AnnotatedString =
    buildAnnotatedString {
        shown.forEachIndexed { index, recipient ->
            // Each recipient starts a line of its own, so a long one that wraps never has the next
            // one begin partway through its last line.
            if (index > 0) append(",\n")
            if (recipient.isSelf) {
                withStyle(SpanStyle(color = accent)) { append(recipient.displayText) }
            } else {
                append(recipient.displayText)
            }
        }
        if (hiddenCount > 0) append("  +$hiddenCount")
    }

/**
 * A To or Cc line of the mail being read.
 *
 * More than one recipient collapses to the first and a count, behind its own arrow, so a mail sent
 * to a whole class does not push the message off the screen; To and Cc open independently.
 * Collapsed, the whole line is what opens it and nothing in it is selectable; open, the recipients
 * are selectable by a long press and only the label and the arrow close it -- the tap that clears
 * a selection would otherwise fold the list away under it as well. One recipient is simply shown,
 * selectable, with no arrow. Nothing is ever cut short: a recipient too long for the line wraps
 * onto the next one, and collapsing only hides the other recipients.
 *
 * A line is one line tall whether it holds one recipient or many, so To and Cc sit as evenly as the
 * header's other lines; collapsed, the whole width of it is the target. Open, the label and the
 * arrow are each a finger's 48dp square, so a short "Cc" is still easy to close: the first line
 * stays where it was and only the space beneath it grows.
 */
@Composable
internal fun MailRecipientRow(label: String, recipients: List<MailRecipient>, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.labelMedium
    var expanded by rememberSaveable { mutableStateOf(false) }
    val collapsible = recipients.size > 1
    val collapsed = collapsible && !expanded
    val ordered = remember(recipients) { headerOrder(recipients) }
    val shown = if (collapsed) ordered.take(1) else ordered
    val line = recipientLine(shown, recipients.size - shown.size, cs.primary)
    val chevronAngle by animateFloatAsState(if (expanded) 180f else 0f, label = "recipientChevron")
    val toggle = Modifier.clickable(role = Role.Button) { expanded = !expanded }
    val closes = if (collapsible && expanded) Modifier.heightIn(min = TouchTarget).then(toggle) else Modifier
    Row(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .then(if (collapsed) toggle else Modifier),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // A finger wide whatever the label, which also lines the To and Cc recipients up.
        Box(Modifier.widthIn(min = TouchTarget).then(closes)) {
            Text(label, style = style, color = cs.outline)
        }
        if (collapsed) {
            Text(line, style = style, color = cs.outline, modifier = Modifier.weight(1f))
        } else {
            SelectionContainer(Modifier.weight(1f)) {
                Text(line, style = style, color = cs.outline)
            }
        }
        if (collapsible) {
            // Open, the label is the button TalkBack reads; the arrow beside it is only another
            // place to tap, not a second, unnamed button.
            Box(Modifier.clearAndSetSemantics {}.width(TouchTarget).then(closes), contentAlignment = Alignment.TopEnd) {
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = cs.outline,
                    modifier = Modifier.size(18.dp).rotate(chevronAngle),
                )
            }
        }
    }
}
