package org.ntust.app.tigerduck.coursecatalog

import okhttp3.Call
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

/** A term's course catalog, relative to the backend's base (`…/v4`), for [CourseCatalogClient]. */
internal interface CourseCatalogApi {

    /** [etag] is the one the stored copy came with, or null for none; a 304 says it still holds. */
    @GET("course-catalog/{semester}")
    fun catalog(
        @Header("Authorization") auth: String,
        @Path("semester") semester: String,
        @Query("language") language: String,
        @Header("If-None-Match") etag: String?,
    ): Call
}
