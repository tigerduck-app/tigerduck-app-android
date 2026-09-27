package org.ntust.app.tigerduck.ui.screen.mail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.mail.compose.ComposeRules
import org.ntust.app.tigerduck.mail.mime.AddressParser

/**
 * The rules the compose screen's recipient fields turn typing into bubbles by. Pure, so they can
 * be tested without a screen.
 */
internal object MailRecipientTokens {
    data class Consumed(val finished: List<String>, val remainder: String)

    /**
     * Splits what has been typed into the recipients it finishes and what is still being typed.
     *
     * `,` and `;` always end a recipient. A space ends one only once what came before it holds an
     * `@` -- so `王大明 <wang@mail.ntust.edu.tw>` can still be typed name first -- and never inside
     * quotes or angle brackets, where it is part of the name or the address. Quotes (with their
     * `\` escapes) and angle brackets are followed exactly as [AddressParser.splitTopLevel]
     * follows them, so a bubble never splits what sending would read as one recipient.
     */
    fun consume(typed: String): Consumed {
        val finished = mutableListOf<String>()
        val current = StringBuilder()
        var inQuote = false
        var depth = 0
        var escaped = false
        for (c in typed) {
            when {
                escaped -> { current.append(c); escaped = false }
                c == '\\' && inQuote -> { current.append(c); escaped = true }
                c == '"' -> { inQuote = !inQuote; current.append(c) }
                c == '<' && !inQuote -> { depth++; current.append(c) }
                c == '>' && !inQuote -> { depth = maxOf(0, depth - 1); current.append(c) }
                !inQuote && depth == 0 && (c == ',' || c == ';' || (c.isWhitespace() && '@' in current)) -> {
                    current.toString().trim().takeIf { it.isNotEmpty() }?.let(finished::add)
                    current.clear()
                }
                else -> current.append(c)
            }
        }
        // Leading whitespace is never the start of an address.
        return Consumed(finished, current.toString().trimStart())
    }

    /** The field's whole value as the view model keeps it: every recipient, comma-separated. */
    fun compose(tokens: List<String>, draft: String): String =
        (tokens + draft.trim()).filter { it.isNotEmpty() }.joinToString(", ")

    /**
     * A value set from outside the field -- a reply's prefilled recipients, a draft reopened -- as
     * bubbles. Only `,`/`;` split here, exactly as sending splits it: the value is already a list,
     * and a space inside it belongs to a name.
     */
    fun tokens(value: String): List<String> =
        AddressParser.splitTopLevel(value).map { it.trim() }.filter { it.isNotEmpty() }
}

/** A capsule on one line -- half a one-line bubble's height -- that stays a rounded box, rather than a stretched pill, when a long address wraps. */
private val BubbleShape = RoundedCornerShape(14.dp)

/**
 * A To, Cc or Bcc field that turns each recipient into a bubble as it is typed: `,`, `;`, a space
 * after an address, the keyboard's Next, or leaving the field finishes one. A bubble sending would
 * refuse is red, by [ComposeRules.sendableAddress]. Tapping a bubble takes it back into the text
 * to edit; its × removes it.
 *
 * The view model still holds the field as one comma-separated string ([value]), so sending,
 * validation, drafts and a reply's prefilled recipients are unchanged -- a prefilled list simply
 * arrives as bubbles. Drawn as an [OutlinedTextFieldDefaults.DecorationBox], so it reads as the
 * same kind of field as Subject and Body beneath it.
 */
@Composable
internal fun MailRecipientTokenField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var tokens by rememberSaveable { mutableStateOf(MailRecipientTokens.tokens(value)) }
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val focusRequester = remember { FocusRequester() }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val colors = OutlinedTextFieldDefaults.colors()

    // What this field has handed the view model that has not come back as [value] yet. [value]
    // trails the typing: the effect below can run after the next keystroke has already landed,
    // and measured against the text on screen that older value of the field's own looks like
    // somebody else's. Reloading from it dropped what had been typed since and turned the rest
    // into a bubble mid-word, and the keyboard then committed the dropped text again.
    val unconfirmed = remember { ArrayDeque<String>() }

    // Only a value this field did not write -- a reply's recipients arriving, a draft reopened.
    LaunchedEffect(value) {
        val echo = unconfirmed.indexOf(value)
        if (echo >= 0) {
            repeat(echo + 1) { unconfirmed.removeFirst() }
            return@LaunchedEffect
        }
        if (value != MailRecipientTokens.compose(tokens, draft.text)) {
            unconfirmed.clear()
            tokens = MailRecipientTokens.tokens(value)
            draft = TextFieldValue()
        }
    }

    // Called after every real edit, never compared against [value] first, which trails the same way.
    fun publish() {
        val composed = MailRecipientTokens.compose(tokens, draft.text)
        unconfirmed.addLast(composed)
        onValueChange(composed)
    }

    // Leaves the value as it was -- the draft was already its last recipient -- so nothing to publish.
    fun finishDraft() {
        val token = draft.text.trim()
        if (token.isEmpty()) return
        tokens = tokens + token
        draft = TextFieldValue()
    }

    fun onDraftChange(typed: TextFieldValue) {
        val textChanged = typed.text != draft.text
        // Nothing is turned into a bubble while the keyboard is still composing -- a Zhuyin name
        // whose characters have not been picked yet. Replacing the text under a live composition
        // restarts the keyboard, which then commits that composition a second time. Once it is
        // committed, this runs again with the composition gone, and splits then.
        val (finished, remainder) =
            if (typed.composition == null) MailRecipientTokens.consume(typed.text) else MailRecipientTokens.Consumed(emptyList(), typed.text)
        if (finished.isEmpty() && remainder == typed.text) {
            draft = typed
            // A cursor move or a composition ending changes nothing the view model holds.
            if (textChanged) publish()
            return
        }
        tokens = tokens + finished
        draft = TextFieldValue(remainder, TextRange(remainder.length))
        publish()
    }

    fun remove(index: Int) {
        if (index !in tokens.indices) return
        tokens = tokens.filterIndexed { i, _ -> i != index }
        publish()
    }

    // Takes a bubble back into the text, finishing whatever was being typed first.
    fun edit(index: Int) {
        finishDraft()
        if (index !in tokens.indices) return
        val token = tokens[index]
        tokens = tokens.filterIndexed { i, _ -> i != index }
        draft = TextFieldValue(token, TextRange(token.length))
        publish()
        focusRequester.requestFocus()
    }

    val textColor = when {
        !enabled -> colors.disabledTextColor
        focused -> colors.focusedTextColor
        else -> colors.unfocusedTextColor
    }
    // What OutlinedTextField reserves above itself when it has a label: the floated label sits
    // across the top border, half of it above the field.
    val labelHalfHeight = with(LocalDensity.current) { MaterialTheme.typography.bodySmall.lineHeight.toDp() / 2 }
    CompositionLocalProvider(LocalTextSelectionColors provides colors.textSelectionColors) {
        BasicTextField(
            value = draft,
            onValueChange = ::onDraftChange,
            modifier = modifier
                .fillMaxWidth()
                // The label is read as part of the field, as OutlinedTextField's is. Each bubble is
                // its own clickable, and so stays separately reachable.
                .semantics(mergeDescendants = true) {}
                .padding(top = labelHalfHeight)
                .defaultMinSize(minWidth = OutlinedTextFieldDefaults.MinWidth, minHeight = OutlinedTextFieldDefaults.MinHeight)
                .focusRequester(focusRequester)
                .onFocusChanged { if (!it.isFocused) finishDraft() },
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
                    value = MailRecipientTokens.compose(tokens, draft.text),
                    innerTextField = {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) {
                            tokens.forEachIndexed { index, token ->
                                RecipientBubble(token, enabled, onEdit = { edit(index) }, onRemove = { remove(index) })
                            }
                            // Wide enough to type into; it moves to a line of its own once the
                            // bubbles leave it less than that.
                            Box(Modifier.widthIn(min = 120.dp)) { innerTextField() }
                        }
                    },
                    enabled = enabled,
                    singleLine = false,
                    visualTransformation = VisualTransformation.None,
                    interactionSource = interactionSource,
                    label = { Text(label) },
                    colors = colors,
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

@Composable
private fun RecipientBubble(token: String, enabled: Boolean, onEdit: () -> Unit, onRemove: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val valid = remember(token) { ComposeRules.sendableAddress(token) != null }
    Surface(
        shape = BubbleShape,
        color = if (valid) cs.primary.copy(alpha = 0.25f) else cs.error.copy(alpha = 0.15f),
        contentColor = if (valid) cs.onSurface else cs.error,
        border = if (valid) null else BorderStroke(1.dp, cs.error.copy(alpha = 0.6f)),
    ) {
        Row(
            modifier = Modifier
                .clickable(enabled = enabled, role = Role.Button, onClick = onEdit)
                .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Wraps rather than truncating, so a long address is never shown cut short.
            Text(token, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.action_remove),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(enabled = enabled, role = Role.Button, onClick = onRemove)
                    .padding(4.dp)
                    .size(16.dp),
            )
        }
    }
}
