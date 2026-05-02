package org.ntust.app.tigerduck.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.intl.Locale

@Composable
fun courseNameForDisplay(text: String, maxChars: Int): String {
    if (!isEnglishUiLanguage()) return text
    return middleEllipsize(text, maxChars)
}

fun middleEllipsize(text: String, maxChars: Int): String {
    if (maxChars < 5 || text.length <= maxChars) return text
    val keep = maxChars - 1 // reserve one char for ellipsis
    val start = (keep + 1) / 2
    val end = keep / 2
    return buildString(maxChars) {
        append(text.take(start))
        append('…')
        append(text.takeLast(end))
    }
}

@Composable
fun isEnglishUiLanguage(): Boolean =
    Locale.current.language.equals("en", ignoreCase = true)
