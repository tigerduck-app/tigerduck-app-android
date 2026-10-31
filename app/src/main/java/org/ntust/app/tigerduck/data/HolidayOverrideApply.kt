package org.ntust.app.tigerduck.data

import org.ntust.app.tigerduck.academic.AcademicCalendarStore

/**
 * Where a full sync's holiday choices land on this phone. Home's sync and the
 * background worker both apply the snapshot through here.
 */
internal object HolidayOverrideApply {

    /**
     * Applies [snapshot], fetched at [fetchedAtMs] under sign-in [signIn], to
     * [calendar]. A choice whose PUT has not landed stays as this phone has it,
     * and a snapshot that may predate one is not applied: see
     * [PendingHolidayOverrides.merged]. Run under [runIfSignedIn], the
     * sign-out's lock: applied after its clear, the departing account's
     * choices would come back.
     */
    fun apply(
        snapshot: Set<Int>,
        fetchedAtMs: Long,
        signIn: Int,
        pending: PendingHolidayOverrides,
        calendar: AcademicCalendarStore,
        runIfSignedIn: (Int, () -> Unit) -> Boolean,
    ) {
        runIfSignedIn(signIn) {
            pending.merged(snapshot, fetchedAtMs, calendar.optedInHolidayIds)
                ?.let(calendar::applySyncedOverrides)
        }
    }
}
