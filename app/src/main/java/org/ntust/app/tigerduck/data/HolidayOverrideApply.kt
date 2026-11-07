package org.ntust.app.tigerduck.data

import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.push.StampedValue
import org.ntust.app.tigerduck.shared.account.SignIn

/**
 * Where a full sync's holiday choices land on this phone. Home's sync and the
 * background worker both apply the snapshot through here.
 */
internal object HolidayOverrideApply {

    /**
     * Applies [snapshot], each holiday's choice with its stamp, field by field (LWW D12): a
     * holiday takes the snapshot's choice, an absent one none, but one this phone keeps, whose own
     * choice stays: one with an edit pending, waiting to go up or being chosen
     * ([PendingHolidayOverrides.keptOver]), or one [keeps] names, its last write acknowledged
     * since the snapshot began. Decided and written in one step,
     * on the thread the Calendar's switch writes a choice on, which ends before the sign-in does
     * ([SignIn.ifCurrent]): applied after the sign-out's clear, the departing account's choices
     * would come back.
     */
    fun applyStamped(
        snapshot: Map<Int, StampedValue<Boolean>>,
        keeps: (Int) -> Boolean,
        signIn: SignIn,
        pending: PendingHolidayOverrides,
        calendar: AcademicCalendarStore,
    ) {
        signIn.ifCurrent {
            val local = calendar.optedInHolidayIds
            val kept = pending.keptOver()
            val merged = (local + snapshot.keys).filterTo(HashSet()) { id ->
                if (id in kept || keeps(id)) id in local else snapshot[id]?.value == true
            }
            if (merged != local) {
                // A holiday this pull replaces loses its edit time with its value.
                pending.forgetEditTimes((merged - local) + (local - merged))
                calendar.applySyncedOverrides(merged)
            }
        }
    }
}
