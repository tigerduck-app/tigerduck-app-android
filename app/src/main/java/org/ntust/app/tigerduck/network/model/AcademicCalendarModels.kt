package org.ntust.app.tigerduck.network.model

import com.google.gson.annotations.SerializedName

/**
 * The school's academic calendar, from `GET /v3/calendar/semesters`.
 *
 * Operator-authored data with no user in it, served unauthenticated — which
 * is why it reaches signed-out users, sync-off users and the fdroid flavour
 * alike. Replaces `AppConstants.CurrentTerm`, which pinned the term and its
 * dates in code and so needed a store release every semester.
 *
 * Every field is nullable or primitive per the persistence checklist: this
 * payload is cached to disk verbatim and re-read after an upgrade, so a
 * response written by an older backend must degrade rather than NPE. Lives
 * in `network.model`, which `proguard-rules.pro` keeps wholesale, so R8
 * cannot rename the fields out from under `@SerializedName`.
 */
data class AcademicCalendarDto(
    /**
     * Newest `updated_at` across both tables as an epoch second, or 0 when
     * the school has published nothing yet. Zero is meaningful: it says "the
     * server has no calendar", which is not the same as "we never fetched".
     */
    @SerializedName("revision") val revision: Long,
    @SerializedName("semesters") val semesters: List<SemesterTermDto>?,
    @SerializedName("holidays") val holidays: List<HolidayDto>?,
)

/**
 * One published term. [start] and [end] are inclusive `YYYY-MM-DD` days in
 * Taipei wall time — dates rather than instants, because every consumer asks
 * "is today in this term", and an instant would invite each platform to pick
 * its own idea of when a day begins.
 */
data class SemesterTermDto(
    @SerializedName("code") val code: String?,
    @SerializedName("start") val start: String?,
    @SerializedName("end") val end: String?,
    @SerializedName("name_zh") val nameZh: String?,
    @SerializedName("name_en") val nameEn: String?,
)

/**
 * A named range on which classes do not meet. A single-day holiday has
 * [start] == [end].
 *
 * Both names are sent because the name is the whole content of the calendar
 * row and of the suppression the user goes looking for; the app picks by
 * locale rather than falling back to one language.
 */
data class HolidayDto(
    @SerializedName("id") val id: Int,
    @SerializedName("name_zh") val nameZh: String?,
    @SerializedName("name_en") val nameEn: String?,
    @SerializedName("start") val start: String?,
    @SerializedName("end") val end: String?,
)
