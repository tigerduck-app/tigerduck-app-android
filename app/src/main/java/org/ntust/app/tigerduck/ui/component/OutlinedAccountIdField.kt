package org.ntust.app.tigerduck.ui.component

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Outlined text field for account-ID style inputs (student ID, library
 * account). Wraps a raw [EditText] so we can request
 * `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` — Compose's [androidx.compose.ui.text.input.KeyboardType]
 * has no equivalent, but visible-password is the only standard inputType
 * that reliably surfaces a number row in Gboard / SwiftKey / Samsung
 * Keyboard for fields that mix letters and digits.
 *
 * Visuals approximate Material 3's outlined text field: stacked label +
 * rounded border that animates color on focus. The label sits above the
 * border (no cutting-the-stroke float) since reproducing that requires
 * either custom Canvas drawing or pulling in com.google.android.material.
 */
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
    var hasFocus by remember { mutableStateOf(false) }

    val onSurfaceArgb = MaterialTheme.colorScheme.onSurface.toArgb()
    val onSurfaceVariantArgb = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val outlineColor = MaterialTheme.colorScheme.outline
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant

    val borderColor by animateColorAsState(
        targetValue = if (hasFocus) primaryColor else outlineColor,
        label = "borderColor",
    )
    val labelColor = if (hasFocus) primaryColor else onSurfaceVariantColor

    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = labelColor,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = if (hasFocus) 2.dp else 1.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(4.dp),
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { ctx ->
                    EditText(ctx).apply {
                        background = null
                        textSize = 16f
                        setSingleLine()
                        setTextColor(onSurfaceArgb)
                        setHintTextColor(onSurfaceVariantArgb)
                        // Tag is used as a "suppress listener" flag while
                        // setText() runs from the update block — avoids
                        // bouncing back into onValueChange and looping when
                        // the parent transforms the value (e.g. uppercase).
                        tag = false
                        addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                            override fun afterTextChanged(s: Editable?) {
                                if (tag == true) return
                                onValueChangeState.value(s?.toString().orEmpty())
                            }
                        })
                        setOnFocusChangeListener { _, focused -> hasFocus = focused }
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
