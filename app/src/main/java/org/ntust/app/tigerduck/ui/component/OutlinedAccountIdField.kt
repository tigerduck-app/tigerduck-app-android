package org.ntust.app.tigerduck.ui.component

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.R

/**
 * Outlined text field for account-ID style inputs (NTUST student ID,
 * library account). Wraps a raw [EditText] inside Material 3's
 * [OutlinedTextFieldDefaults.DecorationBox] so the chrome matches a
 * normal `OutlinedTextField` (floating label that cuts the stroke,
 * animated focus colors, identical height).
 *
 * Both supported account formats share the `[A-Z]\d+` shape (e.g.
 * `B11234567`): one leading letter, then digits. The `inputType` is
 * driven from this:
 *  - first character (length ≤ 1): `TYPE_CLASS_TEXT` so non-Latin IMEs
 *    pick whichever layout the user prefers for the leading letter;
 *  - remaining characters (length ≥ 2): `TYPE_CLASS_NUMBER` so the
 *    rest of the ID is entered on a numeric pad with no IME locale
 *    juggling.
 *
 * The previous implementation pinned `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`
 * to keep Latin keyboards' number row visible. That flag declares the
 * field as Latin-only and forced non-Latin IMEs to flip back to English
 * on every keystroke — unusable. The new strategy keeps the number-row
 * benefit on character 2+ via `TYPE_CLASS_NUMBER` directly, while
 * letting non-Latin IMEs handle the first character normally.
 *
 * For users whose IME doesn't cooperate with the auto-flip, a
 * compatibility toggle is rendered just above the IME (bottom-start of
 * the screen) while the field is focused. Tapping it pins the field to
 * `TYPE_CLASS_TEXT` for the entire input — a plain text keyboard, no
 * numeric flip. State is per-field and ephemeral.
 *
 * Note: `TYPE_CLASS_NUMBER` ignores `TYPE_TEXT_FLAG_CAP_CHARACTERS`.
 * Callers that need uppercase NTUST IDs already force it via
 * `.uppercase()` in their `onValueChange`, so this is a no-op for them.
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

    var isFocused by remember { mutableStateOf(false) }
    var useStandardKeyboard by remember { mutableStateOf(false) }
    var editTextRef by remember { mutableStateOf<EditText?>(null) }

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

                        editTextRef = this
                        var focusInteraction: FocusInteraction.Focus? = null
                        setOnFocusChangeListener { _, focused ->
                            isFocused = focused
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
                    // The compatibility toggle pins to TYPE_CLASS_TEXT to skip
                    // the flip entirely for IMEs that mishandle it.
                    editText.inputType = if (useStandardKeyboard || value.length <= 1) {
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

    if (isFocused && enabled) {
        // The Popup runs in its own window and does not see IME insets
        // directly, so the parent composition reads `WindowInsets.ime` here
        // and feeds the bottom inset into a custom position provider. The
        // provider re-runs whenever its identity changes, which is keyed on
        // the IME inset — that's how the chip rises with the keyboard.
        val density = LocalDensity.current
        val imeBottomPx = WindowInsets.ime.getBottom(density)
        val edgePx = with(density) { 16.dp.roundToPx() }
        val positionProvider = remember(imeBottomPx, edgePx) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val x = if (layoutDirection == LayoutDirection.Ltr) {
                        edgePx
                    } else {
                        windowSize.width - popupContentSize.width - edgePx
                    }
                    val y = windowSize.height - popupContentSize.height - imeBottomPx - edgePx
                    return IntOffset(x, y)
                }
            }
        }
        Popup(
            popupPositionProvider = positionProvider,
            // focusable = false so the chip can be tapped without stealing
            // focus from the EditText (otherwise the IME would dismiss).
            properties = PopupProperties(focusable = false),
        ) {
            FilterChip(
                selected = useStandardKeyboard,
                onClick = {
                    useStandardKeyboard = !useStandardKeyboard
                    // restartInput() makes the IME pick up the new inputType
                    // immediately instead of on the next focus cycle.
                    editTextRef?.let { et ->
                        val imm = et.context
                            .getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        imm?.restartInput(et)
                    }
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Keyboard,
                        contentDescription = null,
                    )
                },
                label = {
                    Text(stringResource(R.string.account_id_use_standard_keyboard))
                },
            )
        }
    }
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
