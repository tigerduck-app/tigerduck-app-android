package org.ntust.app.tigerduck.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * Lays [content] out left to right whatever the device is set to. No right-to-left locale
 * ships, but a phone set to Arabic or Hebrew still has a right-to-left configuration, which
 * Compose reads to mirror every screen around its English strings.
 */
@Composable
internal fun LeftToRight(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
}
