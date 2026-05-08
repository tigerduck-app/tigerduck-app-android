package org.ntust.app.tigerduck.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme

val LocalAccentColor = staticCompositionLocalOf { Color(0xFF007AFF) }

/** Horizontal screen padding, user-adjustable in Settings. Defaults to 12 dp;
 *  bumping it helps on round watches where corners eat content. */
val LocalScreenPadding = staticCompositionLocalOf<Dp> { 12.dp }

fun parseAccent(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (_: IllegalArgumentException) {
    Color(0xFF007AFF)
}

@Composable
fun WearTheme(accent: Color, paddingDp: Int, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalAccentColor provides accent,
        LocalScreenPadding provides paddingDp.dp,
    ) {
        MaterialTheme(content = content)
    }
}
