package org.ntust.app.tigerduck.network

import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.CallAdapter
import retrofit2.Converter
import retrofit2.Retrofit
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap

/**
 * TigerDuck's backend as a Retrofit service [T], one per base URL. The base is
 * read on every call, since the endpoint setting can move it at any time, and
 * a Retrofit holds one base; so each base gets its own, made on first use.
 *
 * Every service sends through [calls], the client the call went through
 * before Retrofit, with its pins, interceptors and timeouts: a Retrofit given
 * no client would make one of its own, unpinned. A service method gives back
 * the OkHttp call unsent ([OkHttpCalls]); the client executes it and reads
 * the response as it always has.
 */
internal class BackendServices<T : Any>(
    private val service: Class<T>,
    private val calls: okhttp3.Call.Factory,
) {
    private val byBase = ConcurrentHashMap<String, T>()

    /** The service at [base], a URL without a trailing slash, as `resolveAnnouncementEndpoint` gives it. */
    fun at(base: String): T = byBase.getOrPut(base) {
        Retrofit.Builder()
            // Retrofit resolves the services' relative paths against a base that
            // ends in a slash; without one it would drop the base's last segment.
            .baseUrl("${base.trimEnd('/')}/")
            .callFactory(calls)
            .addConverterFactory(JsonBodies)
            .addCallAdapterFactory(OkHttpCalls(calls))
            .build()
            .create(service)
    }
}

/**
 * Gives a service method's request back as an OkHttp [okhttp3.Call] of
 * [calls], unsent: the client executes it and reads the [okhttp3.Response]
 * as it always has, its status before its body and its body only where it
 * needs it. Retrofit's own `Call.execute()` reads every body first, a
 * failure's too, so a reply cut short would throw where the client had its
 * status, and a landed upload whose answer can't be read would fail.
 */
private class OkHttpCalls(private val calls: okhttp3.Call.Factory) : CallAdapter.Factory() {
    override fun get(returnType: Type, annotations: Array<out Annotation>, retrofit: Retrofit): CallAdapter<*, *>? {
        if (getRawType(returnType) != okhttp3.Call::class.java) return null
        return object : CallAdapter<ResponseBody, okhttp3.Call> {
            override fun responseType(): Type = ResponseBody::class.java

            // Retrofit makes the request; the call that sends it is the client's own.
            override fun adapt(call: Call<ResponseBody>): okhttp3.Call = calls.newCall(call.request())
        }
    }
}

/**
 * Request bodies as the clients made them before Retrofit: plain [Gson]
 * (nulls left out), as `application/json; charset=utf-8`. A [RequestBody]
 * parameter goes as it is, through Retrofit's own converter. Answers are the
 * clients' own to read ([OkHttpCalls]).
 */
internal object JsonBodies : Converter.Factory() {
    private val gson = Gson()
    private val jsonType = "application/json".toMediaType()

    override fun requestBodyConverter(
        type: Type,
        parameterAnnotations: Array<out Annotation>,
        methodAnnotations: Array<out Annotation>,
        retrofit: Retrofit,
    ): Converter<*, RequestBody> = Converter<Any, RequestBody> { gson.toJson(it).toRequestBody(jsonType) }
}

/**
 * The body a DELETE carries: none to speak of, but a body all the same (length
 * 0, no content type), as OkHttp's `Request.Builder.delete()` sent; Retrofit's
 * plain `@DELETE` would send none, and so no `Content-Length` either.
 */
internal val EmptyBody: RequestBody = ByteArray(0).toRequestBody()
