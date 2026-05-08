package org.ntust.app.tigerduck.wear

import android.app.Application
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.wear.data.WearDebugClockPrefsStore

class WearApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Mirrors DebugClockController.bootstrap() on the phone: a stale
        // override persisted by a debug install must not bleed into release,
        // since the watch has no UI to clear it.
        if (BuildConfig.DEBUG) {
            WearDebugClockPrefsStore(this).load()?.let { AppClock.setOverride(it) }
        }
    }
}
