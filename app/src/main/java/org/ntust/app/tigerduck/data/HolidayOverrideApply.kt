package org.ntust.app.tigerduck.data

import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.shared.account.SignIn

/**
 * Where a full sync's holiday choices land on this phone. Home's sync and the
 * background worker both apply the snapshot through here.
 */
internal object HolidayOverrideApply {

    /**
     * Applies [snapshot], fetched at [fetchedAtMs] under sign-in [signIn], to
     * [calendar]. A choice whose PUT has not landed stays as this phone has it,
     * and a snapshot that may predate one is not applied: see
     * [PendingHolidayOverrides.merged]. In a step that ends before the sign-in
     * does ([SignIn.ifCurrent]): applied after the sign-out's clear, the
     * departing account's choices would come back.
     */
    fun apply(
        snapshot: Set<Int>,
        fetchedAtMs: Long,
        signIn: SignIn,
        pending: PendingHolidayOverrides,
        calendar: AcademicCalendarStore,
    ) {
        signIn.ifCurrent {
            pending.merged(snapshot, fetchedAtMs, calendar.optedInHolidayIds)?.let { merged ->
                // A holiday this pull replaces loses its edit time with its value.
                val before = calendar.optedInHolidayIds
                pending.forgetEditTimes((merged - before) + (before - merged))
                calendar.applySyncedOverrides(merged)
            }
        }
    }
}
