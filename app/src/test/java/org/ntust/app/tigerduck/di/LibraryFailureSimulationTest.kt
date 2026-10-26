package org.ntust.app.tigerduck.di

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.ntust.app.tigerduck.library.FakeLibraryServer
import org.ntust.app.tigerduck.library.MemoryLibraryStore
import org.ntust.app.tigerduck.shared.LibraryService
import org.ntust.app.tigerduck.ui.component.ServerFailureInterceptor
import org.ntust.app.tigerduck.ui.component.ServerFailureSimulator
import org.ntust.app.tigerduck.ui.component.ServerKind
import org.ntust.app.tigerduck.ui.component.SimulatedFailure
import org.ntust.app.tigerduck.ui.screen.library.LibraryPageAccount
import java.io.IOException

/**
 * The server failure simulator's Library row fails the library's own calls, its sign-in and its
 * code, the way the other rows fail theirs: before they leave the phone, in a debug build only.
 */
class LibraryFailureSimulationTest {

    private val store = MemoryLibraryStore()
    private val server = FakeLibraryServer()
    private val noDemo = Interceptor { chain -> chain.proceed(chain.request()) }

    /** The library client as [NetworkModule] builds it, answered by [server]. */
    private fun library(debug: Boolean = true) = LibraryService(
        store,
        extraInterceptors = NetworkModule.libraryInterceptors(noDemo, debug) + server.interceptor,
    )

    @After
    fun resetSimulator() = ServerFailureSimulator.resetAll()

    @Test
    fun `each failing mode stops the sign-in before it reaches the library`() = runBlocking {
        for (mode in listOf(SimulatedFailure.HTTP_500, SimulatedFailure.HTTP_401, SimulatedFailure.HTTP_403, SimulatedFailure.UNREACHABLE)) {
            store.storeExpired(username = "B00015999")
            ServerFailureSimulator.setFailure(mode, ServerKind.LIBRARY)
            try {
                library().generateQRCode()
                fail("$mode let the code through")
            } catch (e: IOException) {
                assertTrue(e.message, e.message!!.contains("LIBRARY"))
            }
        }
        assertEquals(0, server.signIns)
    }

    @Test
    fun `a failing mode stops a code asked for with a live token`() = runBlocking {
        signedIn()
        ServerFailureSimulator.setFailure(SimulatedFailure.HTTP_500, ServerKind.LIBRARY)

        val result = runCatching { library().generateQRCode() }

        assertTrue(result.exceptionOrNull() is IOException)
    }

    @Test
    fun `the page keeps the account through a simulated failure, as through a real outage`() = runBlocking {
        store.storeExpired(username = "B00015999")
        ServerFailureSimulator.setFailure(SimulatedFailure.HTTP_401, ServerKind.LIBRARY)
        val page = LibraryPageAccount(store, library(), { null }, { false })

        val fetch = runCatching { page.fetchCode() }

        assertTrue(fetch.exceptionOrNull() is IOException)
        assertTrue(page.hasAccount)
        assertEquals(false, store.libraryPasswordRefused)
    }

    @Test
    fun `with nothing simulated the code comes`() = runBlocking {
        signedIn()

        assertEquals("code", library().generateQRCode())
    }

    @Test
    fun `another server's failure leaves the library alone`() = runBlocking {
        signedIn()
        ServerFailureSimulator.setFailure(SimulatedFailure.UNREACHABLE, ServerKind.MOODLE)

        assertEquals("code", library().generateQRCode())
    }

    @Test
    fun `a release build never simulates the library`() = runBlocking {
        signedIn()
        ServerFailureSimulator.setFailure(SimulatedFailure.UNREACHABLE, ServerKind.LIBRARY)

        assertEquals("code", library(debug = false).generateQRCode())
    }

    @Test
    fun `Slow holds the code back for its delay, then lets it through`() = runBlocking {
        signedIn()
        ServerFailureSimulator.setFailure(SimulatedFailure.SLOW, ServerKind.LIBRARY)

        assertEquals("code", simulatedLibrary().generateQRCode())

        assertEquals(listOf(ServerFailureSimulator.SLOW_MS), waits)
        assertEquals("nothing went out while it waited", listOf(emptyList<String>()), requestsWhenWaited)
        assertEquals(1, requests.size)
    }

    @Test
    fun `Timeout fails the sign-in after its delay, without reaching the library`() = runBlocking {
        store.storeExpired(username = "B00015999")
        ServerFailureSimulator.setFailure(SimulatedFailure.TIMEOUT, ServerKind.LIBRARY)

        val result = runCatching { simulatedLibrary().generateQRCode() }

        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(listOf(ServerFailureSimulator.TIMEOUT_MS), waits)
        assertEquals(emptyList<String>(), requests)
        assertEquals(0, server.signIns)
    }

    /** What the simulator waited for, each time, and what had gone out to the library by then. */
    private val waits = mutableListOf<Long>()
    private val requests = mutableListOf<String>()
    private val requestsWhenWaited = mutableListOf<List<String>>()

    /** A library client with the simulator's interceptor, whose waits return at once and are recorded. */
    private fun simulatedLibrary() = LibraryService(
        store,
        extraInterceptors = listOf(
            ServerFailureInterceptor(ServerKind.LIBRARY) { ms ->
                waits += ms
                requestsWhenWaited += requests.toList()
            },
            Interceptor { chain ->
                requests += chain.request().url.encodedPath
                server.interceptor.intercept(chain)
            },
        ),
    )

    private fun signedIn() {
        store.libraryUsername = "B00015999"
        store.libraryPassword = "pw"
        store.libraryToken = "token"
        store.libraryTokenExpiry = Long.MAX_VALUE
    }
}
