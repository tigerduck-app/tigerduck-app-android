package org.ntust.app.tigerduck.network

import okhttp3.Call
import org.ntust.app.tigerduck.network.model.SubscriptionListRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The backend's bulletins and bulletin subscriptions, relative to its base
 * (`…/v4`), for [BulletinApiClient]. A subscription's device is the Bearer
 * token's; the rules are saved as a whole list in one PUT ([subscriptionList]),
 * as `/v4` mounts no per-rule route.
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

    /** This device's rules become [list], each kept by its `client_id`. */
    @PUT("bulletin-subscriptions")
    fun subscriptionList(@Header("Authorization") auth: String, @Body list: SubscriptionListRequest): Call
}
