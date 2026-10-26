package org.ntust.app.tigerduck.library

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.service.quicksettings.PendingIntentActivityWrapper
import androidx.core.service.quicksettings.TileServiceCompat
import org.ntust.app.tigerduck.AppLink
import org.ntust.app.tigerduck.activityIntent

/**
 * The Quick Settings tile that opens the library QR, beside [LibraryQrShortcut]. An action, not a
 * switch, so it always shows off. A locked phone is unlocked first: the QR is the student's pass.
 */
class LibraryQrTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        if (isLocked) unlockAndRun(::openLibraryQr) else openLibraryQr()
    }

    // TileServiceCompat takes the PendingIntent from Android 14, which refuses a bare Intent, and
    // the Intent before it. From a service, so in a task of its own.
    private fun openLibraryQr() {
        val intent = AppLink.Library.activityIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        TileServiceCompat.startActivityAndCollapse(
            this,
            PendingIntentActivityWrapper(this, AppLink.Library.uriString.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT, false),
        )
    }
}
