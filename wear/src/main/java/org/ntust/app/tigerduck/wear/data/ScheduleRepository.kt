package org.ntust.app.tigerduck.wear.data

import android.content.Context

/** Process-wide singleton; one instance per Wear app process. */
object ScheduleRepository {

    @Volatile
    private var persistence: SchedulePersistence? = null

    fun get(context: Context): SchedulePersistence {
        return persistence ?: synchronized(this) {
            persistence ?: SchedulePersistence(context.applicationContext).also { persistence = it }
        }
    }
}
