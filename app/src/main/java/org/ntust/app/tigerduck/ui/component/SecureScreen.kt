package org.ntust.app.tigerduck.ui.component

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.ViewParent
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * While [secure] is true, applies `WindowManager.LayoutParams.FLAG_SECURE` to
 * the window hosting this composition: the OS keeps that window out of
 * screenshots and screen recordings, and blanks it in the recent-apps
 * overview. The flag is removed once [secure] goes false or this composable
 * leaves composition.
 *
 * The "hosting window" is resolved per call site: inside a Compose `Dialog`
 * (e.g. the login sheet) it is the dialog's own window — FLAG_SECURE on the
 * Activity window does NOT cover a separate dialog window — and otherwise it
 * is the Activity window.
 *
 * FLAG_SECURE is a per-window flag, so concurrent callers on the same window
 * are reference counted: an inner caller leaving composition must not clear
 * the flag while an outer caller still needs it. Acquire/release run on the
 * composition (main) thread, so the counters need no synchronization.
 */
@Composable
fun SecureScreen(secure: Boolean = true) {
    val window = rememberHostWindow()
    DisposableEffect(window, secure) {
        if (window == null || !secure) {
            onDispose { }
        } else {
            SecureWindowRegistry.acquire(window)
            onDispose { SecureWindowRegistry.release(window) }
        }
    }
}

/**
 * The window backing the current composition: a Compose `Dialog` hosts its
 * content in a view whose parent implements [DialogWindowProvider], exposing
 * the dialog's own window; outside a dialog we fall back to the Activity
 * window.
 */
@Composable
private fun rememberHostWindow(): Window? {
    val view = LocalView.current
    return remember(view) {
        var parent: ViewParent? = view.parent
        while (parent != null) {
            if (parent is DialogWindowProvider) return@remember parent.window
            parent = parent.parent
        }
        view.context.findActivity()?.window
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Per-window FLAG_SECURE reference counter. An entry is created on the first
 * acquire of a window and removed when its last holder releases, so a
 * destroyed Activity's or dismissed dialog's window is not retained.
 */
private object SecureWindowRegistry {
    private val holders = HashMap<Window, Int>()

    fun acquire(window: Window) {
        val count = (holders[window] ?: 0) + 1
        holders[window] = count
        if (count == 1) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    fun release(window: Window) {
        val count = (holders[window] ?: return) - 1
        if (count <= 0) {
            holders.remove(window)
            // The window may already be detached (dialog dismissed / Activity
            // destroyed); clearFlags on a dead window is a harmless no-op.
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            holders[window] = count
        }
    }
}
