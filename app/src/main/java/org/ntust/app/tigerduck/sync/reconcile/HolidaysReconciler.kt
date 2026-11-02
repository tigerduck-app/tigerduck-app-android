package org.ntust.app.tigerduck.sync.reconcile

import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.HolidayOverrideApply
import org.ntust.app.tigerduck.data.PendingHolidayOverrides
import org.ntust.app.tigerduck.sync.SyncPass
import javax.inject.Inject

/**
 * The holidays the user opted back into, through the one apply ([HolidayOverrideApply]).
 *
 * Not gated on any of the per-category sync switches: a holiday exception is a notification
 * setting, not course or assignment data, and the categories the user can turn off do not cover
 * it. A snapshot without the section, from an older backend, leaves the local set. A choice whose
 * PUT has not landed stays as this phone has it, and a snapshot that may predate one is not
 * applied: see [PendingHolidayOverrides.merged].
 */
class HolidaysReconciler @Inject constructor(
    private val pendingHolidayOverrides: PendingHolidayOverrides,
    private val academicCalendar: AcademicCalendarStore,
) : Reconciler {

    override suspend fun reconcile(pass: SyncPass): Boolean {
        val result = pass.result
        val signIn = pass.signIn
        val snapshot = result.holidayOverrides ?: return false
        HolidayOverrideApply.apply(snapshot, result.fetchedAtMs, signIn, pendingHolidayOverrides, academicCalendar)
        return true
    }
}
