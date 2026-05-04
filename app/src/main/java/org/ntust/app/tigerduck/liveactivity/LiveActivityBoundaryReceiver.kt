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

// TODO:
//  **`todaySlotsAfter` does not filter skipped dates**
//  `resolve()` always filters slots with `!it.isSkipped(skippedDates)` before computing scenarios, but `todaySlotsAfter` feeds directly into `nextClassBoundaries` without that filter. If a user has marked the next slot as skipped, the scheduler will still arm an alarm for it. When the alarm fires, `refreshAndWait()` → `resolve()` correctly returns null, so no wrong state is rendered — but an unnecessary wakeup and a cache-load cycle will occur on every skipped class day.


@AndroidEntryPoint
class LiveActivityBoundaryReceiver : BroadcastReceiver() {

    @Inject
    lateinit var liveActivityManager: LiveActivityManager

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
