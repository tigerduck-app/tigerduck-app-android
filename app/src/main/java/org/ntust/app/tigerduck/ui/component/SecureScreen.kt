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
 * content under a view implementing [DialogWindowProvider] — which may be
 * `LocalView.current` itself or one of its ancestors — exposing the dialog's
 * own window; outside a dialog we fall back to the Activity window.
 */
@Composable
private fun rememberHostWindow(): Window? {
    val view = LocalView.current
    return remember(view) {
        if (view is DialogWindowProvider) return@remember view.window
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
    /**
     * [preexisting] records whether FLAG_SECURE was already set on the window
     * when its first holder acquired it (e.g. another screen marked the whole
     * window secure independently of this registry). When true, the last
     * release must NOT clear the flag — doing so would strip protection this
     * registry never added and leave the rest of the window screenshot-able.
     */
    private class Entry(var count: Int, val preexisting: Boolean)

    private val holders = HashMap<Window, Entry>()

    fun acquire(window: Window) {
        val entry = holders[window]
        if (entry != null) {
            entry.count++
            return
        }
        val alreadySecure = (window.attributes.flags and
                WindowManager.LayoutParams.FLAG_SECURE) != 0
        holders[window] = Entry(count = 1, preexisting = alreadySecure)
        if (!alreadySecure) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    fun release(window: Window) {
        val entry = holders[window] ?: return
        entry.count--
        if (entry.count > 0) return
        holders.remove(window)
        if (!entry.preexisting) {
            // The window may already be detached (dialog dismissed / Activity
            // destroyed); clearFlags on a dead window is a harmless no-op.
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
