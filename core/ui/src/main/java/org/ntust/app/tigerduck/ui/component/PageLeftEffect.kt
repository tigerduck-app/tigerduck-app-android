package org.ntust.app.tigerduck.ui.component

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/**
 * Calls [onLeft] when the page leaves the screen for another page.
 *
 * Not when the activity is rebuilt around it: a dark-mode or font-size
 * change takes the composition down and builds it again, so every effect in
 * it runs again, but the page never left. A page that counts its showings
 * pairs this with the effect that reports it shown, so the rebuilt
 * composition's report can be told from a return.
 */
@Composable
fun PageLeftEffect(onLeft: () -> Unit) {
    val activity = LocalContext.current.findActivity()
    val latestOnLeft by rememberUpdatedState(onLeft)
    DisposableEffect(Unit) {
        onDispose {
            if (activity?.isChangingConfigurations != true) latestOnLeft()
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
