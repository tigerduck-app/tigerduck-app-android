package org.ntust.app.tigerduck.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import org.ntust.app.tigerduck.R

/**
 * What a [ColorCodeField] holds: the code of the color being picked, or what
 * is being typed in its place. Typing keeps the text as typed; a color moved
 * some other way, by the square, the slider or a swatch, replaces it with
 * that color's code.
 */
@Stable
class ColorCodeState(hex: Int) {
    var text by mutableStateOf(code(hex))
        private set

    /** The color was moved some other way: show its code. */
    fun show(hex: Int) {
        text = code(hex)
    }

    /**
     * Takes [typed], cut to what the field holds, and returns the color the field then
     * shows, or null while it shows none: a longer paste is read as what is left of it.
     */
    fun type(typed: String): Int? {
        text = typed.take(7)
        return parse(text)
    }

    companion object {
        /** [hex] as the field shows it: "#RRGGBB". */
        fun code(hex: Int): String = "#%06X".format(hex and 0xFFFFFF)

        /** The color a typed code stands for, with or without its "#", or null for anything else. */
        fun parse(code: String): Int? = code.trim().removePrefix("#")
            .takeIf { it.length == 6 && it.all { c -> c in '0'..'9' || c.lowercaseChar() in 'a'..'f' } }
            ?.toInt(16)
    }
}

/**
 * A color's code, which takes one typed in, as TalkBack can where it cannot
 * drag: "#RRGGBB", with or without its "#", in either case. [onColor] gets
 * each color typed in full, as a hex without alpha; anything else stays in
 * the field and moves no color.
 */
@Composable
fun ColorCodeField(
    state: ColorCodeState,
    onColor: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = state.text,
        onValueChange = { typed -> state.type(typed)?.let(onColor) },
        label = { Text(stringResource(R.string.color_picker_hex_code)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = modifier.fillMaxWidth(),
    )
}
