package org.ntust.app.tigerduck.update

import org.ntust.app.tigerduck.data.preferences.AppPreferences

/**
 * Pure gating logic for the "What's new" dialog.
 *
 * The dialog is shown exactly once after an upgrade. A fresh install (last-seen
 * versionCode is the [AppPreferences.WHATS_NEW_UNSET] sentinel) shows nothing —
 * a brand-new user hasn't missed anything.
 */
object WhatsNewGate {

    fun shouldShow(lastSeenVersionCode: Int, currentVersionCode: Int): Boolean {
        if (lastSeenVersionCode == AppPreferences.WHATS_NEW_UNSET) return false
        return lastSeenVersionCode < currentVersionCode
    }
}
