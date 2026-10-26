package org.ntust.app.tigerduck.network

import okhttp3.Call
import okhttp3.RequestBody
import org.ntust.app.tigerduck.network.model.SubscriptionRuleRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The backend's bulletins and bulletin subscriptions, relative to its base
 * (`…/v3`), for [BulletinApiClient]. A subscription's device is the Bearer
 * token's; a DELETE carries an empty body ([EmptyBody]), as OkHttp's
 * `delete()` did.
 */
internal interface BulletinApi {

    @GET("bulletins/taxonomy")
    fun taxonomy(): Call

    /** [includeDeleted] is `true`, or null to leave it out. */
    @GET("bulletins")
    fun bulletins(
        @Query("limit") limit: Int,
        @Query("cursor") cursor: Int?,
        @Query("include_deleted") includeDeleted: String?,
    ): Call

    @GET("bulletins/{id}")
    fun bulletin(@Path("id", encoded = true) id: Int): Call

    @GET("bulletin-subscriptions")
    fun subscriptions(@Header("Authorization") auth: String): Call

    @POST("bulletin-subscriptions")
    fun newSubscription(@Header("Authorization") auth: String, @Body rule: SubscriptionRuleRequest): Call

    @PATCH("bulletin-subscriptions/{id}")
    fun subscription(
        @Header("Authorization") auth: String,
        @Path("id", encoded = true) id: Int,
        @Body rule: SubscriptionRuleRequest,
    ): Call

    @HTTP(method = "DELETE", path = "bulletin-subscriptions/{id}", hasBody = true)
    fun removeSubscription(
        @Header("Authorization") auth: String,
        @Path("id", encoded = true) id: Int,
        @Body empty: RequestBody,
    ): Call
}
