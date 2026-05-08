package org.ntust.app.tigerduck.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.MaterialTheme

val LocalAccentColor = staticCompositionLocalOf { Color(0xFF007AFF) }

fun parseAccent(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (_: IllegalArgumentException) {
    Color(0xFF007AFF)
}

@Composable
fun WearTheme(accent: Color, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAccentColor provides accent) {
        MaterialTheme(content = content)
    }
}
