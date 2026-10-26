package org.ntust.app.tigerduck.ui.component

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

enum class SimulatedFailure(val label: String) {
    NONE("Normal"),
    TIMEOUT("Timeout (30s)"),
    HTTP_500("HTTP 500"),
    HTTP_401("HTTP 401"),
    HTTP_403("HTTP 403"),
    SLOW("Slow (10s delay)"),
    UNREACHABLE("Unreachable"),
}

object ServerFailureSimulator {
    /** How long [SimulatedFailure.TIMEOUT] waits before it fails. */
    const val TIMEOUT_MS = 30_000L

    /** How long [SimulatedFailure.SLOW] holds a call back. */
    const val SLOW_MS = 10_000L

    private val _failures = MutableStateFlow<Map<ServerKind, SimulatedFailure>>(emptyMap())
    val failures: StateFlow<Map<ServerKind, SimulatedFailure>> = _failures.asStateFlow()

    fun failure(server: ServerKind): SimulatedFailure =
        _failures.value[server] ?: SimulatedFailure.NONE

    fun setFailure(failure: SimulatedFailure, server: ServerKind) {
        _failures.update { it + (server to failure) }
    }

    fun resetAll() {
        _failures.value = emptyMap()
    }

    /** [wait] waits out the timeout and the slow answer; a test passes one that returns at once. */
    @Throws(IOException::class)
    suspend fun check(server: ServerKind, wait: suspend (Long) -> Unit = { delay(it) }) {
        val f = failure(server)
        if (f == SimulatedFailure.NONE) return
        when (f) {
            SimulatedFailure.NONE -> {}
            SimulatedFailure.TIMEOUT -> {
                wait(TIMEOUT_MS)
                throw IOException("Simulated timeout for ${server.name}")
            }
            SimulatedFailure.HTTP_500 -> throw IOException("Simulated HTTP 500 for ${server.name}")
            SimulatedFailure.HTTP_401 -> throw IOException("Simulated HTTP 401 for ${server.name}")
            SimulatedFailure.HTTP_403 -> throw IOException("Simulated HTTP 403 for ${server.name}")
            SimulatedFailure.SLOW -> wait(SLOW_MS)
            SimulatedFailure.UNREACHABLE -> throw IOException("Simulated unreachable for ${server.name}")
        }
    }
}

/**
 * Fails each call of a client as [ServerFailureSimulator] says for [server], before it goes out.
 * For a client the app reaches through no call of its own to check first, such as the library's,
 * which lives in `:shared`. It blocks the call's own thread for the timeout and the slow answer.
 */
class ServerFailureInterceptor(
    private val server: ServerKind,
    /** Waits out the timeout and the slow answer; a test passes one that returns at once. */
    private val wait: suspend (Long) -> Unit = { delay(it) },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        runBlocking { ServerFailureSimulator.check(server, wait) }
        return chain.proceed(chain.request())
    }
}
