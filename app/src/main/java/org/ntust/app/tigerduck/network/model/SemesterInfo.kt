package org.ntust.app.tigerduck.network.model

import com.google.gson.annotations.SerializedName

/**
 * One row of `querycourse.ntust.edu.tw/QueryCourse/api/semestersinfo` — the
 * endpoint the official course-query site uses to populate its own semester
 * menu. Rows arrive newest-first and interleave 暑期 terms (`114H`) between
 * the regular ones.
 *
 * Only the two fields the app acts on are modelled; Gson ignores the rest
 * (`EngSemester`, `Static`, `ShowRemind`, `CurrentSemester`).
 *
 * Both fields are upgrade-safe per the persistence checklist — [semester] is
 * nullable and [loginEnable] is a primitive — so a payload missing either key
 * degrades instead of NPE-ing. Lives in the `network.model` package, which
 * `proguard-rules.pro` keeps wholesale, so R8 cannot rename the fields out
 * from under `@SerializedName`.
 */
data class SemesterInfo(
    @SerializedName("Semester") val semester: String?,
    /**
     * Marks the single term the 選課 system is operating on. That term runs
     * *ahead* of the one in session — 選課 for the next term opens weeks
     * before it starts — so this answers "which bucket do 選課清單 course
     * numbers belong in", nothing else.
     */
    @SerializedName("LoginEnable") val loginEnable: Boolean,
)
