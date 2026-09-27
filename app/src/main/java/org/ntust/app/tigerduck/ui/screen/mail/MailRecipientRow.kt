package org.ntust.app.tigerduck.ui.screen.mail

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.util.replaceIosArg

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
 * "To:" / "Cc:" on their own, in the reader's language: the localized "To: %1$@" with nothing in
 * the slot. Every translation puts the value last, so what is left is the label.
 */
internal fun fieldLabel(template: String): String = template.replaceIosArg(1, "").trim()

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
 * A "To:" or "Cc:" line of the mail being read.
 *
 * More than one recipient collapses to the first and a count, behind its own arrow, so a mail sent
 * to a whole class does not push the message off the screen; To and Cc open independently, and
 * the whole line is what opens it. One recipient is simply shown, with no arrow. Nothing is ever
 * cut short: a recipient too long for the line wraps onto the next one, and collapsing only hides
 * the other recipients.
 */
@Composable
internal fun MailRecipientRow(label: String, recipients: List<MailRecipient>, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.labelMedium
    var expanded by rememberSaveable { mutableStateOf(false) }
    val collapsible = recipients.size > 1
    val shown = if (expanded || !collapsible) recipients else recipients.take(1)
    val chevronAngle by animateFloatAsState(if (expanded) 180f else 0f, label = "recipientChevron")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .then(if (collapsible) Modifier.clickable(role = Role.Button) { expanded = !expanded } else Modifier)
            // The platform's 48dp touch height comes from fixed padding around a one-line row, not
            // minimumInteractiveComponentSize: that centres the content, so the one collapsed line
            // started below the top, and once opened the text outgrew the minimum and its first
            // line jumped up. With padding the first line stays exactly where it was and opening
            // only adds lines beneath it.
            .padding(vertical = if (collapsible) 16.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = style, color = cs.outline)
        // Selectable by a long press; a tap still reaches the row, since text selection only
        // claims a press once it has been held.
        SelectionContainer(Modifier.weight(1f)) {
            Text(recipientLine(shown, recipients.size - shown.size, cs.primary), style = style, color = cs.outline)
        }
        if (collapsible) {
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = cs.outline,
                modifier = Modifier.size(18.dp).rotate(chevronAngle),
            )
        }
    }
}
