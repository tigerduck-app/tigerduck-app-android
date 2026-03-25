package org.ntust.app.tigerduck.data.model

import org.ntust.app.tigerduck.AppConstants

data class TimetablePeriod(
    val id: String,
    val startTime: String,
    val endTime: String
) {
    val displayLabel: String get() = id

    companion object {
        val standard: List<TimetablePeriod> = AppConstants.Periods.defaultVisible.mapNotNull { periodId ->
            AppConstants.PeriodTimes.mapping[periodId]?.let { (start, end) ->
                TimetablePeriod(periodId, start, end)
            }
        }

        val all: List<TimetablePeriod> = AppConstants.Periods.chronologicalOrder.mapNotNull { periodId ->
            AppConstants.PeriodTimes.mapping[periodId]?.let { (start, end) ->
                TimetablePeriod(periodId, start, end)
            }
        }

        val byId: Map<String, TimetablePeriod> = all.associateBy { it.id }
    }
}
