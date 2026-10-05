package org.ntust.app.tigerduck.ui.screen.mail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.mail.compose.ComposeRules

/** A capsule on one line -- half a one-line bubble's height -- that stays a rounded box, rather than a stretched pill, when a long address wraps. */
private val BubbleShape = RoundedCornerShape(16.dp)

/** The height a finger is entitled to, kept around a bubble that is drawn shorter, as a Material chip keeps it. */
private val TouchHeight = 48.dp

/**
 * How far a line's contents sit above the middle of its [TouchHeight]. The field already keeps
 * the floated label's lower half clear above the first line and nothing below the last, so a line
 * centred in its own 48dp sat that much low in the field. Raised by half of it, a line of bubbles
 * sits in the middle of the field the way Subject's text does, without the field growing a padding
 * of its own -- which it would only have while the label is floated, and so would jump on focus.
 */
private val LineRaise = 4.dp

/**
 * A To, Cc or Bcc field that turns each recipient into a bubble as it is typed: `,`, `;`, a space
 * after an address, the keyboard's Next, or leaving the field finishes one. A bubble sending would
 * refuse is red, by [ComposeRules.sendableAddress]. Tapping a bubble takes it back into the text,
 * in its own place, to edit; so does Backspace with nothing typed, for the bubble before the
 * cursor. Its × removes it.
 *
 * The recipients live in the view model ([field]); each edit is handed over through [onChange] as
 * a change for it to apply to its latest state, which it answers with what the field became. Drawn
 * as an [OutlinedTextFieldDefaults.DecorationBox], so it reads as the same kind of field as Subject
 * and Body beneath it.
 */
@Composable
internal fun MailRecipientTokenField(
    label: String,
    field: RecipientField,
    onChange: ((RecipientField) -> RecipientField) -> RecipientField,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    // The text being typed, live: its cursor and the keyboard's composition with it. Taken from
    // the view model only when the field is created -- after a rotation, exactly what was there --
    // and from then on written here first and handed over, never read back. [field] reaches the
    // screen a frame behind the typing, and putting that older text back under the keyboard
    // dropped what had been typed since, which the keyboard then committed a second time.
    var draft by remember { mutableStateOf(TextFieldValue(field.draft, TextRange(field.draft.length))) }
    var hadFocus by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val colors = OutlinedTextFieldDefaults.colors()
    val removeLabel = stringResource(R.string.action_remove)

    fun showDraftOf(updated: RecipientField) {
        draft = TextFieldValue(updated.draft, TextRange(updated.draft.length))
    }

    fun onDraftChange(typed: TextFieldValue) {
        // Nothing is turned into a bubble while the keyboard is still composing -- a Zhuyin name
        // whose characters have not been picked yet. Replacing the text under a live composition
        // restarts the keyboard, which then commits that composition a second time. Once it is
        // committed, this runs again with the composition gone, and splits then.
        val (finished, remainder) =
            if (typed.composition == null) MailRecipientTokens.consume(typed.text) else MailRecipientTokens.Consumed(emptyList(), typed.text)
        if (finished.isEmpty() && remainder == typed.text) {
            val textChanged = typed.text != draft.text
            draft = typed
            // A cursor move or a composition ending changes nothing the view model holds.
            if (textChanged) onChange { it.typed(emptyList(), typed.text) }
            return
        }
        draft = TextFieldValue(remainder, TextRange(remainder.length))
        onChange { it.typed(finished, remainder) }
    }

    // Finishing a bubble and reopening one both replace the text being typed, so neither runs
    // under a live composition, for the reason above: the student picks the characters first.
    // Leaving the field ends the composition before this hears of it.
    fun finishDraft() {
        if (draft.composition != null) return
        showDraftOf(onChange { it.finishDraft() })
    }

    fun edit(id: Long) {
        if (draft.composition != null) return
        showDraftOf(onChange { it.edit(id) })
        focusRequester.requestFocus()
    }

    // Backspace with nothing typed reaches no onValueChange -- there is nothing to delete -- so it
    // is taken as the key, which a hardware keyboard sends and so does a soft one, finding the
    // text before the cursor empty.
    fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown || event.key != Key.Backspace) return false
        if (draft.text.isNotEmpty() || draft.composition != null) return false
        val updated = onChange { it.editPrevious() }
        if (updated.draft.isEmpty()) return false
        showDraftOf(updated)
        return true
    }

    val textColor = when {
        !enabled -> colors.disabledTextColor
        focused -> colors.focusedTextColor
        else -> colors.unfocusedTextColor
    }
    // What OutlinedTextField reserves above itself when it has a label: the floated label sits
    // across the top border, half of it above the field.
    val labelHalfHeight = with(LocalDensity.current) { MaterialTheme.typography.bodySmall.lineHeight.toDp() / 2 }
    // Read with the field: without them it announced itself as empty however many it held.
    val finishedText = field.tokens.joinToString(", ") { it.text }
    CompositionLocalProvider(LocalTextSelectionColors provides colors.textSelectionColors) {
        BasicTextField(
            value = draft,
            onValueChange = ::onDraftChange,
            modifier = modifier
                .fillMaxWidth()
                // The label is read as part of the field, as OutlinedTextField's is. Each bubble is
                // its own clickable, and so stays separately reachable.
                .semantics(mergeDescendants = true) {
                    if (finishedText.isNotEmpty()) stateDescription = finishedText
                }
                .padding(top = labelHalfHeight)
                .defaultMinSize(minWidth = OutlinedTextFieldDefaults.MinWidth, minHeight = OutlinedTextFieldDefaults.MinHeight)
                .focusRequester(focusRequester)
                .onFocusChanged {
                    if (hadFocus && !it.isFocused) finishDraft()
                    hadFocus = it.isFocused
                }
                .onPreviewKeyEvent(::onKey),
            enabled = enabled,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = textColor),
            cursorBrush = SolidColor(colors.cursorColor),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = {
                finishDraft()
                defaultKeyboardAction(ImeAction.Next)
            }),
            singleLine = true,
            interactionSource = interactionSource,
            decorationBox = { innerTextField ->
                OutlinedTextFieldDefaults.DecorationBox(
                    // Only whether this is empty matters: it decides where the label sits.
                    value = draft.text.ifEmpty { field.tokens.firstOrNull()?.text.orEmpty() },
                    innerTextField = {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) {
                            @Composable
                            fun Bubble(token: RecipientField.Token) = RecipientBubble(
                                token.text,
                                enabled,
                                removeLabel,
                                onEdit = { edit(token.id) },
                                onRemove = { onChange { it.remove(token.id) } },
                            )
                            val at = field.draftAt.coerceIn(0, field.tokens.size)
                            field.tokens.subList(0, at).forEach { key(it.id) { Bubble(it) } }
                            // Where the typing stands: after the last bubble, or in the place of
                            // one taken back to be edited. One call, wherever that is, so the text
                            // and the keyboard on it stay put as bubbles pass from one side to the
                            // other. Wide enough to type into; it moves to a line of its own once
                            // the bubbles leave it less than that.
                            Box(
                                Modifier.widthIn(min = 120.dp).heightIn(min = TouchHeight).padding(bottom = LineRaise * 2),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                innerTextField()
                            }
                            field.tokens.subList(at, field.tokens.size).forEach { key(it.id) { Bubble(it) } }
                        }
                    },
                    enabled = enabled,
                    // Centres the label while it sits inside the field, and one line of bubbles.
                    singleLine = true,
                    visualTransformation = VisualTransformation.None,
                    interactionSource = interactionSource,
                    label = { Text(label) },
                    colors = colors,
                    // Each line is already a finger's 48dp tall, which puts one line of bubbles
                    // where Subject's text sits; the floated label keeps its own room above.
                    contentPadding = OutlinedTextFieldDefaults.contentPadding(top = 0.dp, bottom = 0.dp),
                    container = {
                        OutlinedTextFieldDefaults.Container(
                            enabled = enabled,
                            isError = false,
                            interactionSource = interactionSource,
                            colors = colors,
                        )
                    },
                )
            },
        )
    }
}

/**
 * One recipient. What is drawn is a bubble with its × inside the end; what is touched is a finger's
 * 48dp, laid over it: the bubble edits, and the × end -- a 48dp square of its own, never sharing
 * an edge with a target smaller than that -- removes. The ripple of each still shows on the
 * bubble itself.
 */
@Composable
private fun RecipientBubble(token: String, enabled: Boolean, removeLabel: String, onEdit: () -> Unit, onRemove: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val valid = remember(token) { ComposeRules.sendableAddress(token) != null }
    val editInteraction = remember { MutableInteractionSource() }
    val removeInteraction = remember { MutableInteractionSource() }
    Box(Modifier.heightIn(min = TouchHeight), contentAlignment = Alignment.Center) {
        Surface(
            shape = BubbleShape,
            color = if (valid) cs.primary.copy(alpha = 0.25f) else cs.error.copy(alpha = 0.15f),
            contentColor = if (valid) cs.onSurface else cs.error,
            border = if (valid) null else BorderStroke(1.dp, cs.error.copy(alpha = 0.6f)),
            // Raised by [LineRaise] in its 48dp, which so reaches further below it than above.
            // Read from the touch targets below instead, each named for what it does.
            modifier = Modifier.padding(bottom = LineRaise * 2).clearAndSetSemantics {},
        ) {
            Row(Modifier.indication(editInteraction, ripple()), verticalAlignment = Alignment.CenterVertically) {
                // Wraps rather than truncating, so a long address is never shown cut short.
                Text(
                    token,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f, fill = false).padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                )
                Box(Modifier.width(TouchHeight), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp).indication(removeInteraction, ripple(bounded = false, radius = 14.dp)),
                    )
                }
            }
        }
        Row(Modifier.matchParentSize()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .semantics { contentDescription = token }
                    .clickable(editInteraction, indication = null, enabled = enabled, role = Role.Button, onClick = onEdit),
            )
            Box(
                Modifier
                    .width(TouchHeight)
                    .fillMaxHeight()
                    .semantics { contentDescription = "$removeLabel $token" }
                    .clickable(removeInteraction, indication = null, enabled = enabled, role = Role.Button, onClick = onRemove),
            )
        }
    }
}
