package org.ntust.app.tigerduck.network.moodle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Moodle pages open signed in through a one-time key, as the Moodle app opens them, or as they are. */
@OptIn(ExperimentalCoroutinesApi::class)
class MoodleAutologinTest {

    private val page = "https://moodle2.ntust.edu.tw/course/view.php?id=5"
    private val key = MoodleAutologin.Key(userId = 7, value = "onetime", autologinUrl = "https://moodle2.ntust.edu.tw/admin/tool/mobile/autologin.php")

    private var requests = 0
    private var answer: suspend () -> MoodleAutologin.Key? = { key }
    private var generation = 0
    private var privateToken: String? = "private"
    private var nowMs = 1_000_000_000L

    /** Whether a Moodle token is stored, which a private token can be fetched for. */
    private var moodleToken = true
    private var fetches = 0
    private var fetch: suspend () -> Boolean = {
        privateToken = "private"
        true
    }

    private fun TestScope.autologin() = MoodleAutologin(
        keySource = {
            requests++
            answer()
        },
        scope = backgroundScope,
        now = { nowMs },
        signInGeneration = { generation },
        hasPrivateToken = { privateToken != null },
        canFetchPrivateToken = { moodleToken },
        fetchPrivateToken = {
            fetches++
            fetch()
        },
    )

    @Test
    fun `a Moodle page opens behind a one-time key`() = runTest {
        val opened = autologin().url(page).toHttpUrl()

        assertEquals("/admin/tool/mobile/autologin.php", opened.encodedPath)
        assertEquals("7", opened.queryParameter("userid"))
        assertEquals("onetime", opened.queryParameter("key"))
        assertEquals(page, opened.queryParameter("urltogo"))
    }

    @Test
    fun `a page off the Moodle site or not over https asks for no key`() = runTest {
        val autologin = autologin()

        assertEquals("https://example.com/a", autologin.url("https://example.com/a"))
        assertEquals("http://moodle2.ntust.edu.tw/a", autologin.url("http://moodle2.ntust.edu.tw/a"))
        assertEquals(0, requests)
    }

    @Test
    fun `Moodle gives one key in six minutes, so the next open within them goes as it is`() = runTest {
        val autologin = autologin()
        autologin.url(page)
        nowMs += 5 * 60_000L

        assertEquals(page, autologin.url(page))
        assertEquals(1, requests)

        nowMs += 61_000L
        assertTrue(autologin.url(page).contains("key=onetime"))
        assertEquals(2, requests)
    }

    @Test
    fun `a slow key opens the page as it is, and still counts once it comes`() = runTest {
        val gate = CompletableDeferred<Unit>()
        answer = {
            gate.await()
            key
        }
        val autologin = autologin()

        assertEquals(page, autologin.url(page))
        // An open while the request is out asks for none of its own.
        val meanwhile = backgroundScope.async { autologin.url(page) }
        advanceTimeBy(MoodleAutologin.KEY_WAIT_MS + 1)
        assertEquals(page, meanwhile.await())
        gate.complete(Unit)
        runCurrent()

        assertEquals(page, autologin.url(page))
        assertEquals(1, requests)
    }

    @Test
    fun `a key that arrives after a sign-out is dropped`() = runTest {
        answer = {
            generation++
            key
        }

        assertEquals(page, autologin().url(page))
    }

    @Test
    fun `a key that lands once the private token is gone, as a sign-out clears it, is dropped`() = runTest {
        answer = {
            privateToken = null
            key
        }

        assertEquals(page, autologin().url(page))
    }

    @Test
    fun `a sign-out read at once drops a key that lands after it, though nothing has heard of it yet`() = runTest {
        val gate = CompletableDeferred<Unit>()
        answer = {
            gate.await()
            key
        }
        val autologin = autologin()
        val opening = backgroundScope.async { autologin.url(page) }
        runCurrent()
        // The sign-out moves the generation where it runs, before any collector sees it.
        generation++
        gate.complete(Unit)

        assertEquals(page, opening.await())
    }

    @Test
    fun `a failed request opens the page as it is and lets the next open ask again`() = runTest {
        answer = { throw java.io.IOException("offline") }
        val autologin = autologin()

        assertEquals(page, autologin.url(page))
        answer = { key }
        assertTrue(autologin.url(page).contains("key=onetime"))
    }

    @Test
    fun `a key whose sign-in link leaves the Moodle site is never used`() = runTest {
        answer = { key.copy(autologinUrl = "https://evil.example/autologin.php") }

        assertEquals(page, autologin().url(page))
    }

    // --- a Moodle token stored without its private token, as builds before 2.4.0 left it ---

    @Test
    fun `an open fetches the missing private token, then opens behind a key`() = runTest {
        privateToken = null

        assertTrue(autologin().url(page).contains("key=onetime"))
        assertEquals(1, fetches)
        assertEquals(1, requests)
    }

    @Test
    fun `opens with the private token stored fetch nothing`() = runTest {
        autologin().url(page)

        assertEquals(0, fetches)
    }

    @Test
    fun `without a Moodle token nothing is fetched and the page opens as it is`() = runTest {
        privateToken = null
        moodleToken = false

        assertEquals(page, autologin().url(page))
        assertEquals(0, fetches)
        assertEquals(0, requests)
    }

    @Test
    fun `two opens at once share one fetch`() = runTest {
        privateToken = null
        val gate = CompletableDeferred<Unit>()
        fetch = {
            gate.await()
            privateToken = "private"
            true
        }
        val autologin = autologin()
        val first = backgroundScope.async { autologin.url(page) }
        val second = backgroundScope.async { autologin.url(page) }
        runCurrent()
        gate.complete(Unit)

        // One key between them: the other open goes as it is, as for any two opens at once.
        assertEquals(1, listOf(first.await(), second.await()).count { it.contains("key=onetime") })
        assertEquals(1, fetches)
    }

    @Test
    fun `an open waits past the key's wait while the fetch is under way`() = runTest {
        privateToken = null
        fetch = {
            delay(MoodleAutologin.KEY_WAIT_MS + 2_000L)
            privateToken = "private"
            true
        }

        assertTrue(autologin().url(page).contains("key=onetime"))
    }

    @Test
    fun `a fetch too slow opens the page as it is and asks for no key, and the next open gets one at once`() = runTest {
        privateToken = null
        fetch = {
            delay(MoodleAutologin.PRIVATE_TOKEN_WAIT_MS + 5_000L)
            privateToken = "private"
            true
        }
        val autologin = autologin()

        assertEquals(page, autologin.url(page))
        // Moodle would count a key nobody waits on against the next six minutes.
        assertEquals(0, requests)
        advanceTimeBy(5_001L)
        runCurrent()

        assertTrue(autologin.url(page).contains("key=onetime"))
        assertEquals(1, fetches)
    }

    @Test
    fun `a failed fetch opens the page as it is, and the next open fetches again`() = runTest {
        privateToken = null
        fetch = { throw java.io.IOException("offline") }
        val autologin = autologin()

        assertEquals(page, autologin.url(page))
        assertEquals(0, requests)
        fetch = {
            privateToken = "private"
            true
        }

        assertTrue(autologin.url(page).contains("key=onetime"))
        assertEquals(2, fetches)
    }

    @Test
    fun `a pair that comes without a private token is not fetched again for the same sign-in`() = runTest {
        privateToken = null
        fetch = { false }
        val autologin = autologin()

        assertEquals(page, autologin.url(page))
        assertEquals(page, autologin.url(page))
        assertEquals(1, fetches)
        assertEquals(0, requests)

        // A new sign-in harvests a pair of its own; should it come without one too, it is tried once.
        generation++
        autologin.url(page)
        assertEquals(2, fetches)
    }

    @Test
    fun `a sign-out during the fetch opens the page as it is`() = runTest {
        privateToken = null
        fetch = {
            privateToken = "private"
            generation++
            true
        }

        assertEquals(page, autologin().url(page))
        assertEquals(0, requests)
    }

    @Test
    fun `a fetch started before a sign-out is not shared with an open after it`() = runTest {
        privateToken = null
        val gate = CompletableDeferred<Unit>()
        fetch = {
            gate.await()
            false
        }
        val autologin = autologin()
        val departing = backgroundScope.async { autologin.url(page) }
        runCurrent()
        // The student signs out and in again while the departing account's fetch is out.
        generation++
        fetch = {
            privateToken = "private"
            true
        }

        assertTrue(autologin.url(page).contains("key=onetime"))
        assertEquals(2, fetches)
        gate.complete(Unit)
        assertEquals(page, departing.await())
    }

    // --- what an open reports while it waits on the fetch ---

    private val waits = mutableListOf<Boolean>()

    private fun record(waiting: Boolean) {
        waits += waiting
    }

    @Test
    fun `an open that fetches the private token reports a wait from the fetch until its key comes`() = runTest {
        privateToken = null
        val keyGate = CompletableDeferred<Unit>()
        answer = {
            keyGate.await()
            key
        }
        val autologin = autologin()
        val open = backgroundScope.async { autologin.signedIn(page, ::record) }
        runCurrent()

        // The private token is in and the key is on its way: still waiting.
        assertEquals(1, fetches)
        assertEquals(listOf(true), waits)
        keyGate.complete(Unit)

        assertTrue(open.await()!!.contains("key=onetime"))
        assertEquals(listOf(true, false), waits)
    }

    @Test
    fun `an open with its private token stored reports no wait`() = runTest {
        autologin().signedIn(page, ::record)

        assertEquals(1, requests)
        assertEquals(emptyList<Boolean>(), waits)
    }

    @Test
    fun `an open with no fetch to wait for reports no wait`() = runTest {
        val autologin = autologin()
        privateToken = null

        // Off the Moodle site.
        autologin.signedIn("https://example.com/a", ::record)
        assertEquals(emptyList<Boolean>(), waits)
        // Without a Moodle token to fetch for.
        moodleToken = false
        autologin.signedIn(page, ::record)
        assertEquals(emptyList<Boolean>(), waits)
        // After a pair that came without a private token, for the same sign-in.
        moodleToken = true
        fetch = { false }
        autologin.signedIn(page, ::record)
        assertEquals(listOf(true, false), waits)
        waits.clear()
        autologin.signedIn(page, ::record)

        assertEquals(emptyList<Boolean>(), waits)
    }

    @Test
    fun `the wait ends when the fetch fails`() = runTest {
        privateToken = null
        fetch = { throw java.io.IOException("offline") }

        assertNull(autologin().signedIn(page, ::record))
        assertEquals(listOf(true, false), waits)
    }

    @Test
    fun `the wait ends when the fetch is too slow`() = runTest {
        privateToken = null
        fetch = {
            delay(MoodleAutologin.PRIVATE_TOKEN_WAIT_MS + 5_000L)
            privateToken = "private"
            true
        }

        assertNull(autologin().signedIn(page, ::record))
        assertEquals(listOf(true, false), waits)
    }

    @Test
    fun `the wait ends at a sign-out during the fetch`() = runTest {
        privateToken = null
        fetch = {
            privateToken = "private"
            generation++
            true
        }

        assertNull(autologin().signedIn(page, ::record))
        assertEquals(listOf(true, false), waits)
    }

    @Test
    fun `the wait ends when the key that follows the fetch never comes`() = runTest {
        privateToken = null
        answer = {
            delay(MoodleAutologin.KEY_WAIT_MS + 1_000L)
            key
        }

        assertNull(autologin().signedIn(page, ::record))
        assertEquals(listOf(true, false), waits)
    }

    @Test
    fun `the wait ends when the open is cancelled`() = runTest {
        privateToken = null
        val gate = CompletableDeferred<Unit>()
        fetch = {
            gate.await()
            privateToken = "private"
            true
        }
        val autologin = autologin()
        val open = backgroundScope.async { autologin.signedIn(page, ::record) }
        runCurrent()
        assertEquals(listOf(true), waits)

        open.cancel()
        runCurrent()

        assertEquals(listOf(true, false), waits)
    }

    // --- the request ---

    private var sentBody: String? = null
    private var sentUrl: okhttp3.HttpUrl? = null

    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val body = when (request.url.queryParameter("wsfunction")) {
            "core_webservice_get_site_info" -> """{"userid":7,"functions":[]}"""
            MoodleAutologin.KEY_FUNCTION -> {
                sentUrl = request.url
                sentBody = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                """{"key":"onetime","autologinurl":"https://moodle2.ntust.edu.tw/admin/tool/mobile/autologin.php"}"""
            }
            else -> "{}"
        }
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }.build()

    private val tokens = object : MoodleWebservice.TokenSource {
        override fun stored() = "t1"
        override suspend fun renew(rejected: String?) = "t1"
    }

    @Test
    fun `the private token goes in the request body, never its address`() = runTest {
        val got = MoodleAutologin.requestKey(MoodleWebservice({ client }, tokens)) { "private" }

        assertEquals(key, got)
        assertEquals("wstoken=t1&privatetoken=private", sentBody)
        assertFalse(sentUrl.toString().contains("private"))
    }

    @Test
    fun `without a private token nothing is asked`() = runTest {
        assertNull(MoodleAutologin.requestKey(MoodleWebservice({ client }, tokens)) { null })
        assertNull(sentBody)
    }

    @Test
    fun `the wait for the next key counts down from a key handed out and is none before any`() = runTest {
        val autologin = autologin()
        assertEquals(0L, autologin.msUntilKeyDue())

        autologin.url(page)
        assertEquals(MoodleAutologin.KEY_INTERVAL_MS, autologin.msUntilKeyDue())

        nowMs += 60_000L
        assertEquals(MoodleAutologin.KEY_INTERVAL_MS - 60_000L, autologin.msUntilKeyDue())

        nowMs += MoodleAutologin.KEY_INTERVAL_MS
        assertEquals(0L, autologin.msUntilKeyDue())
    }

    @Test
    fun `a request that brought no key leaves nothing to wait for`() = runTest {
        answer = { null }
        val autologin = autologin()

        autologin.url(page)

        assertEquals(0L, autologin.msUntilKeyDue())
    }
}
