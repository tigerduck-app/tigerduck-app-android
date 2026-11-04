package org.ntust.app.tigerduck.sync.reconcile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.HolidayOverrideApply
import org.ntust.app.tigerduck.data.PendingHolidayOverrides
import org.ntust.app.tigerduck.push.StampedValue
import org.ntust.app.tigerduck.sync.SyncField
import org.ntust.app.tigerduck.sync.SyncMode
import org.ntust.app.tigerduck.sync.SyncPass
import javax.inject.Inject

/**
 * The holidays the user opted back into, through the one apply ([HolidayOverrideApply]).
 *
 * Not gated on any of the per-category sync switches: a holiday exception is a notification
 * setting, not course or assignment data, and the categories the user can turn off do not cover
 * it. A snapshot without the section, from an older backend, leaves the local set. It is applied
 * holiday by holiday, but those this phone keeps (LWW D12): a choice whose PUT has not landed
 * stays as this phone has it; see [HolidayOverrideApply.applyStamped].
 *
 * On the main thread, where the Calendar's switch writes a choice, so a switch turned between the
 * merge's check and its write cannot be erased by the older set (its upload would then send the
 * value written over). Home's pass runs there already; the background worker's moves there for it.
 */
class HolidaysReconciler @Inject constructor(
    private val pendingHolidayOverrides: PendingHolidayOverrides,
    private val academicCalendar: AcademicCalendarStore,
) : Reconciler {

    override suspend fun reconcile(pass: SyncPass): Boolean {
        // Without the section, from an older backend, the local set stays.
        val stamped = pass.result.holidays ?: return false
        val holds = pass.holds()
        when (pass.mode) {
            SyncMode.FOREGROUND -> apply(pass, holds, stamped)
            SyncMode.BACKGROUND -> withContext(Dispatchers.Main) { apply(pass, holds, stamped) }
        }
        return true
    }

    // The snapshot's choices field by field (LWW D12).
    private fun apply(pass: SyncPass, holds: PullHolds, stamped: Map<Int, StampedValue<Boolean>>) {
        HolidayOverrideApply.applyStamped(
            stamped, holds.serverHlc, { holds.keeps(SyncField.holiday(it)) }, pass.signIn, pendingHolidayOverrides, academicCalendar,
        )
    }
}
