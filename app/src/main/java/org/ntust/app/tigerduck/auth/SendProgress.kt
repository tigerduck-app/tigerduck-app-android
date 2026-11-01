package org.ntust.app.tigerduck.auth

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.RequestBody
import okio.BufferedSink
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * What a refresh call got to before it failed. One that began to connect
 * and failed before writing the request never sent the token: a connect
 * timeout, to a host that drops the packets, raises the same
 * SocketTimeoutException as a reply that never came.
 */
internal class SendProgress : EventListener() {
    @Volatile private var connecting = false
    @Volatile private var writing = false

    val failedBeforeWriting: Boolean get() = connecting && !writing

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connecting = true
    }

    override fun requestHeadersStart(call: Call) {
        writing = true
    }
}

// OkHttp sends a request again after some replies, whatever
// retryOnConnectionFailure says: a 503 with Retry-After: 0, and a 307 or a
// 308 with its body. It sends none whose body is one-shot.
internal fun RequestBody.oneShot(): RequestBody = object : RequestBody() {
    override fun contentType() = this@oneShot.contentType()
    override fun contentLength() = this@oneShot.contentLength()
    override fun writeTo(sink: BufferedSink) = this@oneShot.writeTo(sink)
    override fun isOneShot() = true
}
