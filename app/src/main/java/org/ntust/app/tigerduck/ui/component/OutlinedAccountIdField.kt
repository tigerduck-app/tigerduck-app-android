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
 * Uses a two-mode `inputType` based on the current value length:
 *  - first character (length ≤ 1): `TYPE_CLASS_TEXT` so non-Latin IMEs
 *    pick whichever layout the user prefers for the leading letter;
 *  - remaining characters (length ≥ 2): `TYPE_CLASS_NUMBER` so the
 *    rest of the ID is entered on a numeric pad with no IME locale
 *    juggling.
 *
 * Both supported account formats fit this shape: NTUST IDs are
 * `[A-Z]\d+` (e.g. `B11234567`), and library accounts are all-numeric.
 *
 * The previous implementation pinned `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`
 * to keep Latin keyboards' number row visible. That flag declares the
 * field as Latin-only and forced non-Latin IMEs to flip back to English
 * on every keystroke — unusable. The new strategy keeps the number-row
 * benefit on character 2+ via `TYPE_CLASS_NUMBER` directly, while
 * letting non-Latin IMEs handle the first character normally.
 *
 * Note: `TYPE_CLASS_NUMBER` ignores `TYPE_TEXT_FLAG_CAP_CHARACTERS`.
 * Callers that need uppercase NTUST IDs already force it via
 * `.uppercase()` in their `onValueChange`, so this is a no-op for them;
 * library accounts are documented as case-insensitive.
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
                            override fun beforeTextChanged(
                                s: CharSequence?,
                                start: Int,
                                count: Int,
                                after: Int
                            ) {
                            }

                            override fun onTextChanged(
                                s: CharSequence?,
                                start: Int,
                                before: Int,
                                count: Int
                            ) {
                            }

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
                    // Position 1 lets the user's IME choose layout for the
                    // leading letter; positions 2+ flip to a pure numeric pad.
                    // Re-runs on every value change so the swap is automatic.
                    editText.inputType = if (value.length <= 1) {
                        InputType.TYPE_CLASS_TEXT or capitalization.toInputTypeFlag()
                    } else {
                        InputType.TYPE_CLASS_NUMBER
                    }
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
