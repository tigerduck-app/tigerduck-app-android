package org.ntust.app.tigerduck.academic

import okhttp3.Call
import retrofit2.http.GET
import retrofit2.http.Header

/** The school's academic calendar, relative to the backend's base (`…/v3`), for [AcademicCalendarStore]. */
internal interface AcademicCalendarApi {

    /** [etag] is the one the last payload came with, or null for none; a 304 says it still holds. */
    @GET("calendar/semesters")
    fun semesters(
        @Header("Accept") accept: String,
        @Header("If-None-Match") etag: String?,
    ): Call
}
