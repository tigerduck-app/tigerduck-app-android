package org.ntust.app.tigerduck.ui.component

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.R

/**
 * Outlined text field for account-ID style inputs (student ID, library
 * account). Wraps a raw [EditText] inside Material 3's
 * [OutlinedTextFieldDefaults.DecorationBox] so the chrome matches a
 * normal `OutlinedTextField` (floating label that cuts the stroke,
 * animated focus colors, identical height).
 *
 * Uses `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` so Gboard / SwiftKey /
 * Samsung pin the number row throughout typing — alphabetic-only
 * variations (text/email/uri) auto-flip back to letters after each
 * digit, which is unusable for IDs like NTUST `B11234567` where the
 * digits run continuously.
 *
 * VISIBLE_PASSWORD does silently ignore the IME capitalization flag,
 * so first-character auto-cap won't fire. NTUST mode forces uppercase
 * via a `.uppercase()` transform in the caller anyway; for the library
 * field this means the user types lowercase by default (acceptable —
 * library accounts are not case-sensitive in practice).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutlinedAccountIdField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    enabled: Boolean = true,
    autofillHint: String? = null,
) {
    val onValueChangeState = rememberUpdatedState(onValueChange)
    val onImeActionState = rememberUpdatedState(onImeAction)
    val interactionSource = remember { MutableInteractionSource() }
    val coroutineScope = rememberCoroutineScope()

    val onSurfaceArgb = MaterialTheme.colorScheme.onSurface.toArgb()
    val onSurfaceVariantArgb = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()

    OutlinedTextFieldDefaults.DecorationBox(
        value = value,
        innerTextField = {
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { ctx ->
                    EditText(ctx).apply {
                        background = null
                        // Strip EditText's own padding so the
                        // DecorationBox-supplied padding governs layout.
                        setPadding(0, 0, 0, 0)
                        includeFontPadding = false
                        textSize = 16f
                        setSingleLine()
                        setTextColor(onSurfaceArgb)
                        setHintTextColor(onSurfaceVariantArgb)
                        // Tag is a "suppress listener" flag while setText()
                        // runs from the update block — avoids bouncing back
                        // into onValueChange and looping when the parent
                        // transforms the value (e.g. uppercase).
                        tag = false

                        var focusInteraction: FocusInteraction.Focus? = null
                        setOnFocusChangeListener { _, focused ->
                            coroutineScope.launch {
                                if (focused) {
                                    val interaction = FocusInteraction.Focus()
                                    focusInteraction = interaction
                                    interactionSource.emit(interaction)
                                } else {
                                    focusInteraction?.let {
                                        interactionSource.emit(FocusInteraction.Unfocus(it))
                                    }
                                    focusInteraction = null
                                }
                            }
                        }

                        addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                            override fun afterTextChanged(s: Editable?) {
                                if (tag == true) return
                                onValueChangeState.value(s?.toString().orEmpty())
                            }
                        })

                        setOnEditorActionListener { _, actionId, _ ->
                            when (actionId) {
                                EditorInfo.IME_ACTION_NEXT,
                                EditorInfo.IME_ACTION_DONE,
                                EditorInfo.IME_ACTION_GO,
                                EditorInfo.IME_ACTION_SEARCH,
                                EditorInfo.IME_ACTION_SEND -> {
                                    onImeActionState.value()
                                    true
                                }
                                else -> false
                            }
                        }
                    }
                },
                update = { editText ->
                    editText.inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                        capitalization.toInputTypeFlag()
                    editText.imeOptions = imeAction.toEditorInfoFlag()
                    if (autofillHint != null) {
                        editText.setAutofillHints(autofillHint)
                    }
                    editText.isEnabled = enabled
                    if (editText.text.toString() != value) {
                        editText.tag = true
                        editText.setText(value)
                        editText.setSelection(value.length)
                        editText.tag = false
                    }
                },
            )
        },
        enabled = enabled,
        singleLine = true,
        visualTransformation = VisualTransformation.None,
        interactionSource = interactionSource,
        label = { Text(label) },
        trailingIcon = if (enabled && value.isNotEmpty()) {
            {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(
                        imageVector = Icons.Filled.Cancel,
                        contentDescription = stringResource(R.string.action_clear_text),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else null,
        container = {
            OutlinedTextFieldDefaults.Container(
                enabled = enabled,
                isError = false,
                interactionSource = interactionSource,
            )
        },
    )
}

private fun KeyboardCapitalization.toInputTypeFlag(): Int = when (this) {
    KeyboardCapitalization.Characters -> InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
    KeyboardCapitalization.Sentences -> InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    KeyboardCapitalization.Words -> InputType.TYPE_TEXT_FLAG_CAP_WORDS
    else -> 0
}

private fun ImeAction.toEditorInfoFlag(): Int = when (this) {
    ImeAction.Next -> EditorInfo.IME_ACTION_NEXT
    ImeAction.Done -> EditorInfo.IME_ACTION_DONE
    ImeAction.Go -> EditorInfo.IME_ACTION_GO
    ImeAction.Search -> EditorInfo.IME_ACTION_SEARCH
    ImeAction.Send -> EditorInfo.IME_ACTION_SEND
    ImeAction.Previous -> EditorInfo.IME_ACTION_PREVIOUS
    else -> EditorInfo.IME_ACTION_UNSPECIFIED
}
