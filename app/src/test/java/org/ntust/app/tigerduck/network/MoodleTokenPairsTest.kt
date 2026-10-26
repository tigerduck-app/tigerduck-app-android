package org.ntust.app.tigerduck.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.auth.InMemoryStoredPasswordLatch

/** The private token a sign-in harvests is stored beside the wstoken it came with, and no other. */
@OptIn(ExperimentalCoroutinesApi::class)
class MoodleTokenPairsTest {

    private class Store : MoodleTokenPairStore {
        override var token: String? = null
        override var privateToken: String? = null
        override var studentId: String? = "B11300001"
        override var password: String? = "pw"
        override fun storeIfSignedIn(studentId: String, token: String, privateToken: String?): Boolean {
            if (this.studentId != studentId) return false
            this.token = token
            this.privateToken = privateToken
            return true
        }
    }

    private val store = Store()
    private val pairs = MoodleTokenPairs(store)
    private var harvests = 0
    private var privateToken: String? = "private"

    private val latch = InMemoryStoredPasswordLatch()

    private fun TestScope.coordinator() = MoodleTokenCoordinator(
        tokens = pairs,
        login = { id, _, _ ->
            val token = "token-$id-${harvests++}"
            pairs.harvested(token, privateToken)
            token
        },
        scope = backgroundScope,
        now = { 0L },
        pauseMs = 300_000L,
        passwordLatch = latch,
    )

    @Test
    fun `a sign-in stores the private token with its token`() = runTest(UnconfinedTestDispatcher()) {
        val token = coordinator().obtain("b11300001", "pw")

        assertEquals(token, store.token)
        assertEquals("private", store.privateToken)
    }

    @Test
    fun `a renewal replaces both, and one harvested without a private token clears the old`() =
        runTest(UnconfinedTestDispatcher()) {
            store.token = "dead"
            store.privateToken = "old private"
            privateToken = null
            val fresh = coordinator().renew(rejected = "dead")

            assertEquals(fresh, store.token)
            assertNull(store.privateToken)
        }

    @Test
    fun `a renewal that lands after a sign-out stores neither`() = runTest(UnconfinedTestDispatcher()) {
        store.token = "dead"
        val coordinator = MoodleTokenCoordinator(
            tokens = pairs,
            login = { id, _, _ ->
                pairs.harvested("fresh", "private")
                store.studentId = null
                "fresh-$id"
            },
            scope = backgroundScope,
            now = { 0L },
            pauseMs = 300_000L,
            passwordLatch = latch,
        )
        runCatching { coordinator.renew(rejected = "dead") }

        assertEquals("dead", store.token)
        assertNull(store.privateToken)
    }

    @Test
    fun `a private token harvested with another token is not stored with this one`() {
        pairs.harvested("first", "private of first")
        pairs.token = "second"

        assertEquals("second", store.token)
        assertNull(store.privateToken)
    }
}
