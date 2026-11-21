package org.ntust.app.tigerduck.coursechanges

import okhttp3.Call
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/** The school's course changes for its current term, relative to the backend's base, for [CourseChangeStore]. */
internal interface CourseChangeApi {

    /**
     * [kinds] lists the kinds beyond the first five this build reads, which the feed otherwise
     * sends as notes (ADR 0031); its comma goes as it is, as the iPhone sends it. [etag] is the
     * one the stored feed came with, or null for none; a 304 says it still holds.
     */
    @GET("calendar/course-changes")
    fun courseChanges(
        @Query("kinds", encoded = true) kinds: String,
        @Header("Accept") accept: String,
        @Header("If-None-Match") etag: String?,
    ): Call
}
