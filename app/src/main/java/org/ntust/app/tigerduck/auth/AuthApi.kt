package org.ntust.app.tigerduck.auth

import okhttp3.Call
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * The backend's sign-in, relative to the issuer (`…/v3`), for
 * [AuthTokenManager.login]. The refresh is not here: it goes out once, on
 * OkHttp alone, with what it counts before sending; see
 * [AuthTokenManager]'s `refreshClient`.
 */
internal interface AuthApi {

    /** [body] is the login JSON the manager writes, `moodle_private_token` left out when null. */
    @POST("auth/login")
    fun login(@Body body: RequestBody): Call
}
