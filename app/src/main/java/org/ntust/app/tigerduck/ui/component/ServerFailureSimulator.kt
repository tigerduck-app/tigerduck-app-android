package org.ntust.app.tigerduck.ui.component

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    @Throws(IOException::class)
    suspend fun check(server: ServerKind) {
        val f = failure(server)
        if (f == SimulatedFailure.NONE) return
        when (f) {
            SimulatedFailure.NONE -> {}
            SimulatedFailure.TIMEOUT -> {
                delay(30_000)
                throw IOException("Simulated timeout for ${server.name}")
            }
            SimulatedFailure.HTTP_500 -> throw IOException("Simulated HTTP 500 for ${server.name}")
            SimulatedFailure.HTTP_401 -> throw IOException("Simulated HTTP 401 for ${server.name}")
            SimulatedFailure.HTTP_403 -> throw IOException("Simulated HTTP 403 for ${server.name}")
            SimulatedFailure.SLOW -> delay(10_000)
            SimulatedFailure.UNREACHABLE -> throw IOException("Simulated unreachable for ${server.name}")
        }
    }
}
