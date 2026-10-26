package org.ntust.app.tigerduck.library

import android.content.Context
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import org.ntust.app.tigerduck.AppLink
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.activityIntent
import org.ntust.app.tigerduck.util.logSummary

/**
 * The library QR opened from outside the app, as the iPhone's App Intents are to open it
 * (tigerduck-app/tigerduck-app#308): this launcher shortcut, on a long press of the app's icon,
 * and the Quick Settings tile ([LibraryQrTileService]). Both open [AppLink.Library], the link the
 * Library widget opens, so a student signed out of the library gets its sign-in there.
 */
internal object LibraryQrShortcut {
    const val ID = "library_qr"

    private const val TAG = "LibraryQrShortcut"

    /** The launcher shortcut, labelled in the language [context] has now. */
    fun shortcut(context: Context): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, ID)
            .setShortLabel(context.getString(R.string.library_virtual_pass_title))
            .setLongLabel(context.getString(R.string.library_qr_content_description))
            .setIcon(IconCompat.createWithResource(context, R.drawable.shortcut_library_qr))
            .setIntent(AppLink.Library.activityIntent(context))
            .build()

    /**
     * Puts [shortcut] on the app's icon, again at each start of the app's page, so its labels
     * follow the app's language. A launcher that takes no shortcuts leaves the app as it was.
     */
    fun publish(context: Context) {
        try {
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut(context))
        } catch (e: Exception) {
            Log.w(TAG, "Could not publish the library QR shortcut: ${e.logSummary()}")
        }
    }
}
