package org.ntust.app.tigerduck.wear

import android.app.Application
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.wear.data.WearDebugClockPrefsStore

class WearApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        WearDebugClockPrefsStore(this).load()?.let { AppClock.setOverride(it) }
    }
}
