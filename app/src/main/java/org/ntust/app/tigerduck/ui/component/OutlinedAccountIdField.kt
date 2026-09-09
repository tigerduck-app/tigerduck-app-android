package org.ntust.app.tigerduck.ui.component

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
 *  - empty value (typing the leading letter):
 *    `TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` — Android's
 *    "Latin-only, not masked" combo. Forces the IME to ASCII for the
 *    single leading letter so users on Chinese/Japanese/etc. don't
 *    have to manually switch IME language;
 *  - non-empty value (typing the 2nd char onward): `TYPE_CLASS_NUMBER`
 *    so the rest of the ID is entered on a numeric pad with no IME
 *    locale juggling.
 *
 * Earlier this field pinned `VISIBLE_PASSWORD` for the *entire* input,
 * which made non-Latin IMEs flip back to English on every keystroke —
 * unusable. The Latin force is now scoped to a single keystroke (the
 * leading letter); after that the field switches to a numeric pad,
 * which doesn't have the flip-back problem. The standard-keyboard
 * toggle bypasses both: it pins to plain `TYPE_CLASS_TEXT` for users
 * whose IME doesn't cooperate with the auto-flip at all.
 *
 * For users whose IME doesn't cooperate with the auto-flip, a
 * compatibility toggle is rendered at the top-end of the screen
 * while the field is focused. Tapping it pins the field to
 * `TYPE_CLASS_TEXT` for the entire input — a plain text keyboard, no
 * numeric flip. State is per-field and ephemeral.
 *
 * Callers can opt out of the floating chip by passing
 * `useStandardKeyboardOverride` (e.g. dialogs that prefer to render the
 * toggle inline next to their action buttons). When non-null, the
 * caller owns the boolean and the field skips its built-in popup; the
 * field still calls `restartInput` on the underlying `EditText` so the
 * IME picks up the new `inputType` immediately.
 *
 * Note: `TYPE_CLASS_NUMBER` ignores `TYPE_TEXT_FLAG_CAP_CHARACTERS`.
 * Callers that need uppercase NTUST IDs already force it via
 * `.uppercase()` in their `onValueChange`, so this is a no-op for them.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    useStandardKeyboardOverride: Boolean? = null,
) {
    val onValueChangeState = rememberUpdatedState(onValueChange)
    val onImeActionState = rememberUpdatedState(onImeAction)
    val interactionSource = remember { MutableInteractionSource() }
    val coroutineScope = rememberCoroutineScope()

    val onSurfaceArgb = MaterialTheme.colorScheme.onSurface.toArgb()
    val onSurfaceVariantArgb = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()

    var isFocused by remember { mutableStateOf(false) }
    var internalUseStandardKeyboard by remember { mutableStateOf(false) }
    val useStandardKeyboard = useStandardKeyboardOverride ?: internalUseStandardKeyboard

    // Tracks the last inputType pushed to the EditText so the update block
    // only flips it (and asks the IME to reconnect) when the value actually
    // changes. The hot empty→non-empty transition flips inputType from
    // VISIBLE_PASSWORD to TYPE_CLASS_NUMBER mid-keystroke; without an explicit
    // restartInput, GBoard/Samsung IME can drop the composing span.
    // -1 means "not applied yet" so the first write always goes through and
    // skips the IME reconnect (no IME connection on initial compose).
    var lastAppliedInputType by remember { mutableStateOf(-1) }

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
                    // Empty value lets the user's IME choose layout for the
                    // leading letter; non-empty flips to a pure numeric pad.
                    // The compatibility toggle pins to TYPE_CLASS_TEXT to skip
                    // the flip entirely for IMEs that mishandle it.
                    val newInputType = computeAccountInputType(
                        useStandardKeyboard, value, capitalization,
                    )
                    if (newInputType != lastAppliedInputType) {
                        editText.inputType = newInputType
                        if (lastAppliedInputType != -1) {
                            val imm = editText.context
                                .getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                            imm?.restartInput(editText)
                        }
                        lastAppliedInputType = newInputType
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

    // Built-in floating chip is only rendered when the caller hasn't
    // taken over the toggle (override == null). Also gated on IME
    // visibility so the chip doesn't linger after back-to-dismiss while
    // the EditText keeps focus.
    if (useStandardKeyboardOverride == null && isFocused && enabled && WindowInsets.isImeVisible) {
        // The chip lives in its own popup window. When the field is
        // hosted inside a Compose `Dialog` (e.g. a login popup card),
        // that popup attaches to the dialog's window and the position
        // provider's coordinates are interpreted relative to it — which
        // is why a naïve "top-end" target lands at the top-end of the
        // dialog rather than the screen.
        //
        // To pin to the screen regardless of host window, we compute a
        // screen-absolute target (top-end, clear of the status bar) and
        // convert it to parent-window-relative by subtracting the parent
        // window's location on screen. Status-bar inset is read from the
        // activity's decor view so it reflects the activity's own inset
        // (dialog windows sit below the status bar already and report 0).
        val composeView = LocalView.current
        val context = LocalContext.current
        val activity = remember(context) { context.findActivity() }
        val edgePx = with(LocalDensity.current) { 16.dp.roundToPx() }
        val positionProvider = remember(composeView, activity, edgePx) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val parentLoc = IntArray(2)
                    composeView.rootView.getLocationOnScreen(parentLoc)

                    val activityRoot = activity?.window?.decorView ?: composeView.rootView
                    val statusBarHeight = ViewCompat.getRootWindowInsets(activityRoot)
                        ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
                    val screenWidth = composeView.context.resources.displayMetrics.widthPixels

                    val targetScreenY = statusBarHeight + edgePx
                    val targetScreenX = if (layoutDirection == LayoutDirection.Ltr) {
                        screenWidth - popupContentSize.width - edgePx
                    } else {
                        edgePx
                    }

                    return IntOffset(
                        targetScreenX - parentLoc[0],
                        targetScreenY - parentLoc[1],
                    )
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
                    internalUseStandardKeyboard = !internalUseStandardKeyboard
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
                // Default unselected container is transparent, which makes
                // the chip illegible against the underlying card. Use an
                // opaque neutral tone for unselected, and override the
                // selected fill to `primary` so the highlighted state is
                // unambiguously the *more* colourful one across themes —
                // the M3 default selected color (`secondaryContainer`) can
                // appear less saturated than a tinted `surfaceContainerHigh`
                // in dynamic themes, which inverts the visual cue.
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        }
    }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private fun computeAccountInputType(
    useStandard: Boolean,
    value: String,
    capitalization: KeyboardCapitalization,
): Int = when {
    // Standard-keyboard mode is the user's escape hatch for IMEs that
    // mishandle the auto-flip. Don't force Latin in that mode — let the
    // IME render whatever script the user actually wants.
    useStandard -> InputType.TYPE_CLASS_TEXT or capitalization.toInputTypeFlag()
    // Empty value = the leading letter slot. Pin the IME to Latin/ASCII
    // for this single keystroke so users on Chinese/Japanese/etc. IMEs
    // don't have to manually switch language to type the prefix.
    // `VISIBLE_PASSWORD` is Android's documented "Latin-only, not masked"
    // input flag. As soon as the leading letter is typed (value becomes
    // non-empty) we flip to the numeric pad so the digit slot is one tap
    // away — the user wants the keyboard to switch immediately after the
    // first char, not after the second. Trade-off: if the user backspaces
    // all digits down to the lone letter and wants to retype the prefix,
    // they need the standard-keyboard toggle to escape the numeric pad.
    value.isEmpty() -> InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            capitalization.toInputTypeFlag()
    // 2nd char onward: numeric pad, no IME-locale juggling.
    else -> InputType.TYPE_CLASS_NUMBER
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
