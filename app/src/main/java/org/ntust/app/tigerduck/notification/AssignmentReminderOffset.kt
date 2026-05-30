package org.ntust.app.tigerduck.notification

import androidx.annotation.StringRes
import org.ntust.app.tigerduck.R

/**
 * Fixed reminder lead times before an assignment's due date. Mirrors the
 * iOS `AssignmentReminderOffset` so both platforms share the same set of
 * user-toggleable choices and the same notification body wording per
 * offset bucket.
 *
 * Ordered longest → shortest so settings UI and scheduling iterate in a
 * stable direction. [rawValue] is what gets persisted; never reorder
 * existing entries or rename their `rawValue`.
 */
enum class AssignmentReminderOffset(
    val rawValue: String,
    val milliseconds: Long,
    @param:StringRes val labelRes: Int,
    @param:StringRes val bodyRes: Int,
) {
    HR48("hr48", 48 * 3600 * 1000L,
        R.string.assignment_reminder_offset_48h,
        R.string.notification_assignment_reminder_body_48h),
    HR24("hr24", 24 * 3600 * 1000L,
        R.string.assignment_reminder_offset_24h,
        R.string.notification_assignment_reminder_body_24h),
    HR16("hr16", 16 * 3600 * 1000L,
        R.string.assignment_reminder_offset_16h,
        R.string.notification_assignment_reminder_body_16h),
    HR8("hr8", 8 * 3600 * 1000L,
        R.string.assignment_reminder_offset_8h,
        R.string.notification_assignment_reminder_body_multi_hour),
    HR4("hr4", 4 * 3600 * 1000L,
        R.string.assignment_reminder_offset_4h,
        R.string.notification_assignment_reminder_body_multi_hour),
    HR2("hr2", 2 * 3600 * 1000L,
        R.string.assignment_reminder_offset_2h,
        R.string.notification_assignment_reminder_body_multi_hour),
    HR1("hr1", 1 * 3600 * 1000L,
        R.string.assignment_reminder_offset_1h,
        R.string.notification_assignment_reminder_body_multi_hour),
    MIN30("min30", 30 * 60 * 1000L,
        R.string.assignment_reminder_offset_30m,
        R.string.notification_assignment_reminder_body_30m),
    MIN15("min15", 15 * 60 * 1000L,
        R.string.assignment_reminder_offset_15m,
        R.string.notification_assignment_reminder_body_15m),
    MIN10("min10", 10 * 60 * 1000L,
        R.string.assignment_reminder_offset_10m,
        R.string.notification_assignment_reminder_body_15m),
    MIN5("min5", 5 * 60 * 1000L,
        R.string.assignment_reminder_offset_5m,
        R.string.notification_assignment_reminder_body_5m);

    companion object {
        /** Default selection — matches iOS (6 high-signal offsets). */
        val DEFAULTS: Set<AssignmentReminderOffset> = setOf(HR48, HR24, HR8, HR2, HR1, MIN30)

        fun fromRawValue(raw: String?): AssignmentReminderOffset? =
            raw?.let { v -> entries.firstOrNull { it.rawValue == v } }
    }
}
