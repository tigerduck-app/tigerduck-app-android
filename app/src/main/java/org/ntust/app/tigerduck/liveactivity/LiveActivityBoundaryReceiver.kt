package org.ntust.app.tigerduck.liveactivity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

@AndroidEntryPoint
class LiveActivityBoundaryReceiver : BroadcastReceiver() {

    @Inject lateinit var liveActivityManager: LiveActivityManager

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                withTimeout(10_000) { liveActivityManager.refreshAndWait() }
            } catch (t: Throwable) {
                android.util.Log.w("LiveActivityBoundary", "boundary refresh failed", t)
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }
}
