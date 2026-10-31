package org.ntust.app.tigerduck.coursechanges

import okhttp3.Call
import retrofit2.http.GET
import retrofit2.http.Header

/** The school's course changes for its current term, relative to the backend's base (`…/v3`), for [CourseChangeStore]. */
internal interface CourseChangeApi {

    /** [etag] is the one the stored feed came with, or null for none; a 304 says it still holds. */
    @GET("calendar/course-changes")
    fun courseChanges(
        @Header("Accept") accept: String,
        @Header("If-None-Match") etag: String?,
    ): Call
}
