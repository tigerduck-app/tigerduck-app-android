package org.ntust.app.tigerduck.push

import okhttp3.Call
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.HTTP
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The backend's device and sync endpoints, relative to its base (`…/v3`), for
 * [PushApiClient]. `Authorization` is the Bearer header the client resolves
 * before the call. Paths and queries are given already encoded, as the
 * client wrote its URLs before Retrofit. A DELETE carries an empty body
 * ([org.ntust.app.tigerduck.network.EmptyBody]), as OkHttp's `delete()` did.
 */
@JvmSuppressWildcards
internal interface PushApi {

    @POST("devices/anonymous")
    fun anonymousDevice(@Body request: AnonymousDeviceRequest): Call

    @POST("devices/register")
    fun registeredDevice(@Header("Authorization") auth: String, @Body request: DeviceRegisterRequest): Call

    @HTTP(method = "DELETE", path = "devices/{deviceId}", hasBody = true)
    fun removeDevice(
        @Header("Authorization") auth: String,
        @Path("deviceId", encoded = true) deviceId: String,
        @Body empty: RequestBody,
    ): Call

    @PUT("sync/holiday-overrides/{holidayId}")
    fun holidayOverride(
        @Header("Authorization") auth: String,
        @Path("holidayId", encoded = true) holidayId: Int,
        @Body request: HolidayOverrideRequest,
    ): Call

    @PATCH("devices/{deviceId}/preferences")
    fun devicePreferences(
        @Header("Authorization") auth: String,
        @Path("deviceId", encoded = true) deviceId: String,
        @Body request: UpdateDevicePreferencesRequest,
    ): Call

    @PATCH("auth/credentials")
    fun credentials(@Header("Authorization") auth: String, @Body credentials: Map<String, String?>): Call

    @PATCH("sync/assignments/{assignmentId}/override")
    fun assignmentOverride(
        @Header("Authorization") auth: String,
        @Path("assignmentId", encoded = true) assignmentId: Int,
        @Body override: Map<String, String>,
    ): Call

    @PATCH("sync/courses/{courseId}/override")
    fun courseOverride(
        @Header("Authorization") auth: String,
        @Path("courseId", encoded = true) courseId: String,
        @Body override: Map<String, Any?>,
    ): Call

    /** The same override as [courseOverride], whose answer says whether the backend kept the field. */
    @PATCH("sync/courses/{moodleId}/override")
    fun courseIgnore(
        @Header("Authorization") auth: String,
        @Path("moodleId", encoded = true) moodleId: String,
        @Body override: Map<String, Any>,
    ): Call

    /** [body] is the JSON already written, which the upload's fingerprint was taken of. */
    @POST("sync/assignments/upload")
    fun assignmentsUpload(@Header("Authorization") auth: String, @Body body: RequestBody): Call

    /** [protocol] "2" for an upload whose adds carry their time; null leaves the header out. */
    @POST("sync/courses/upload")
    fun coursesUpload(
        @Header("Authorization") auth: String,
        @Body body: RequestBody,
        @Header("X-TigerSync-Protocol") protocol: String?,
    ): Call

    /** [hlc] the hide's time, in microseconds since the epoch; null sends none. */
    @HTTP(method = "DELETE", path = "sync/courses/{courseKey}", hasBody = true)
    fun removeCourse(
        @Header("Authorization") auth: String,
        @Path("courseKey", encoded = true) courseKey: String,
        @Body empty: RequestBody,
        @Query("hlc") hlc: Long?,
    ): Call

    /** [semester] form-encoded already (`URLEncoder`), or null for every term. */
    @HTTP(method = "DELETE", path = "sync/courses", hasBody = true)
    fun removeCourses(
        @Header("Authorization") auth: String,
        @Query("semester", encoded = true) semester: String?,
        @Body empty: RequestBody,
    ): Call
}
