package org.ntust.app.tigerduck.network

import okhttp3.Call
import okhttp3.EventListener

/**
 * The calls one sign-in has started, to cancel together; one started after [cancelAll] is
 * cancelled as it starts. Told of each as it starts ([callStart]), once the call is whole.
 */
internal class RunningCalls : EventListener() {
    private val calls = mutableListOf<Call>()
    private var cancelled = false

    @Synchronized
    override fun callStart(call: Call) {
        if (cancelled) call.cancel() else calls += call
    }

    @Synchronized
    fun cancelAll() {
        cancelled = true
        calls.forEach(Call::cancel)
    }
}
