package org.ntust.app.tigerduck.auth

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.AuthTokenManager.SignInAttempt
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.data.preferences.V3TokenStore.Companion.KEY_ACCESS_TOKEN
import org.ntust.app.tigerduck.data.preferences.V3TokenStore.Companion.KEY_EXPIRES_AT
import org.ntust.app.tigerduck.data.preferences.V3TokenStore.Companion.KEY_ISSUED_BY
import org.ntust.app.tigerduck.data.preferences.V3TokenStore.Companion.KEY_REFRESH_TOKEN
import org.ntust.app.tigerduck.data.preferences.V3TokenStore.Companion.KEY_REFRESH_TOKEN_SENDS
import org.ntust.app.tigerduck.data.preferences.V3TokenStore.Companion.KEY_STUDENT_ID
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.Closeable
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlin.concurrent.thread

/**
 * How [AuthTokenManager] keeps the v3 pair through a refresh. The backend
 * rotates the refresh token on every refresh, answers the old one once more
 * inside a 60 s grace, and revokes every session of the device when the old
 * one comes back after that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AuthTokenManagerRefreshTest {

    private lateinit var context: Application
    private lateinit var file: SavesRecorder
    private lateinit var tokens: V3TokenStore
    private var clock = 1_800_000_000_000L

    /** The refresh tokens the fake backend received, in order. */
    private val sentRefreshTokens = mutableListOf<String>()
    private var relogins = 0

    /** Runs as each refresh reaches the fake backend, before its reply. */
    private var onRefreshSent: () -> Unit = {}

    /** Runs as each sign-in reaches the fake backend, before its reply. */
    private var onLoginSent: () -> Unit = {}

    /**
     * Runs as a request resolves the backend's address from [endpointReadPrefs], which a refresh
     * does after it is marked on its way and before it counts its send on disk.
     */
    private var onEndpointRead: () -> Unit = {}

    /** [AppPreferences] whose read of the API endpoint setting runs [onEndpointRead]. */
    private fun endpointReadPrefs(): AppPreferences = AppPreferences(object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val real = super.getSharedPreferences(name, mode)
            return object : SharedPreferences by real {
                override fun getString(key: String, defValue: String?): String? {
                    if (key == "announcementApiBaseUrlOverride") onEndpointRead()
                    return real.getString(key, defValue)
                }
            }
        }
    })

    /** The requests the client of [networkManager] began. */
    private val calls = AtomicInteger()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        storePair(expiresAt = 0L)
    }

    /**
     * Puts "access-1" and "refresh-1" on disk, with no count of its sends as a
     * version before the count stored it, and starts counting saves afresh.
     */
    private fun storePair(expiresAt: Long) {
        val prefs = context.getSharedPreferences("v3-tokens-test", Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, "access-1")
            .putString(KEY_REFRESH_TOKEN, "refresh-1")
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .remove(KEY_REFRESH_TOKEN_SENDS)
            .commit()
        file = SavesRecorder(prefs)
        tokens = V3TokenStore(file)
        sentRefreshTokens.clear()
        relogins = 0
    }

    @Test
    fun `a refresh writes the new pair in one commit`() = runTest {
        val manager = manager(Reply.Pair("access-2", "refresh-2"))

        assertEquals("Bearer access-2", manager.authHeader())

        // The send's count, then the pair, whose count starts over, with the backend that issued
        // it: the one the refresh went to.
        assertEquals(listOf(setOf(KEY_REFRESH_TOKEN_SENDS), PAIR_KEYS + KEY_ISSUED_BY), file.commits)
        assertEquals(0, file.applies)
        assertEquals(listOf("refresh-1"), sentRefreshTokens)
        assertEquals("refresh-2", tokens.refreshToken)
    }

    @Test
    fun `a pair whose commit fails is still used`() = runTest {
        // SharedPreferences holds an edit in memory before it writes the file, and a disk
        // that refused this write would refuse a relogin's pair or a clear as well.
        file.failCommitsOf = KEY_ACCESS_TOKEN
        val manager = manager(Reply.Pair("access-2", "refresh-2"))

        assertEquals("Bearer access-2", manager.authHeader())

        assertEquals(0, relogins)
        assertEquals("refresh-2", tokens.refreshToken)
    }

    @Test
    fun `a send whose count the disk refuses does not go out, and a sign-in takes its place`() = runTest {
        // Counted in memory only, it would be forgotten by a restart. A sign-in sends no
        // refresh token, so a full disk does not leave the phone without a session.
        file.failCommitsOf = KEY_REFRESH_TOKEN_SENDS
        val manager = manager(Reply.Pair("access-2", "refresh-2"), reloginSucceeds = true)

        assertEquals("Bearer access-relogin", manager.authHeader())

        assertEquals(emptyList<String>(), sentRefreshTokens)
        assertEquals(1, relogins)
    }

    @Test
    fun `a resend whose count the disk refuses keeps the tokens when the sign-in fails`() = runTest {
        // The first send is counted and its reply lost; the disk then refuses the resend's count.
        file.failCommitsOf = KEY_REFRESH_TOKEN_SENDS
        file.failCommitsAfter = 1
        val manager = manager(Reply.Lost)

        assertNull(manager.authHeader())

        assertEquals(listOf("refresh-1"), sentRefreshTokens)
        assertEquals(1, relogins)
        assertEquals("refresh-1", tokens.refreshToken)
    }

    @Test
    fun `a sign-in that fails after a refused count keeps the tokens and waits out the pause`() = runTest {
        file.failCommitsOf = KEY_REFRESH_TOKEN_SENDS
        val manager = manager(Reply.Pair("access-2", "refresh-2"))

        assertNull(manager.authHeader())

        assertEquals(emptyList<String>(), sentRefreshTokens)
        assertEquals(1, relogins)
        assertEquals("refresh-1", tokens.refreshToken)
        // Put back in memory too, so the next refresh does not take it as sent.
        assertEquals(0, tokens.refreshTokenSends)
        assertNull(manager.authHeader())
        assertEquals(1, relogins)
    }

    @Test
    fun `a sign-in writes its pair in one commit`() = runTest {
        val manager = manager(Reply.Pair("access-2", "refresh-2"))

        manager.login("B00000000", moodleToken = "moodle", moodlePrivateToken = null)

        // With the backend that issued it and the student it is for, for the sign-out's unregister.
        assertEquals(listOf(PAIR_KEYS + KEY_ISSUED_BY + KEY_STUDENT_ID), file.commits)
        assertEquals(0, file.applies)
        assertEquals("refresh-2", tokens.refreshToken)
    }

    @Test
    fun `a refresh whose reply was lost is sent once more with the same token and keeps that pair`() = runTest {
        val manager = manager(Reply.Lost, Reply.Pair("access-2", "refresh-2"))

        assertEquals("Bearer access-2", manager.authHeader())

        assertEquals(listOf("refresh-1", "refresh-1"), sentRefreshTokens)
        assertEquals(0, relogins)
        assertEquals("refresh-2", tokens.refreshToken)
    }

    @Test
    fun `a second lost reply is not sent a third time`() = runTest {
        val manager = manager(Reply.Lost, Reply.Lost, Reply.Pair("access-2", "refresh-2"))

        assertNull(manager.authHeader())

        // The resend may have spent the backend's one grace: sign in again as before.
        assertEquals(listOf("refresh-1", "refresh-1"), sentRefreshTokens)
        assertEquals(1, relogins)
        assertNull(tokens.refreshToken)
    }

    @Test
    fun `a lost reply and then a 5xx signs in again`() = runTest {
        val manager = manager(Reply.Lost, Reply.Status(503))

        assertNull(manager.authHeader())

        // The first send may have rotated the token, and a later refresh would send it after the grace.
        assertEquals(listOf("refresh-1", "refresh-1"), sentRefreshTokens)
        assertEquals(1, relogins)
        assertNull(tokens.refreshToken)
    }

    @Test
    fun `a resend the backend did not use leaves the pair its last send`() = runTest {
        for (resend in listOf(Reply.Status(429), Reply.Unsent)) {
            storePair(expiresAt = 0L)
            val manager = manager(Reply.Lost, resend, Reply.Pair("access-2", "refresh-2"))

            // Only the first send may have reached the backend.
            assertNull("$resend", manager.authHeader())
            assertEquals("$resend", 0, relogins)
            assertEquals("$resend", "refresh-1", tokens.refreshToken)
            assertEquals("$resend", 1, tokens.refreshTokenSends)
            clock += 60_000

            assertEquals("$resend", "Bearer access-2", manager.authHeader())
            assertEquals("$resend", 0, relogins)
        }
    }

    @Test
    fun `a gateway 5xx and then a lost reply send the token no third time`() = runTest {
        // A 504 from a proxy may come after the backend rotated, and the lost reply may
        // hide its second rotation: a resend would be the token's third use.
        val manager = manager(Reply.Status(504), Reply.Lost, Reply.Pair("access-2", "refresh-2"))
        assertNull(manager.authHeader())
        clock += 60_000

        assertNull(manager.authHeader())

        assertEquals(listOf("refresh-1", "refresh-1"), sentRefreshTokens)
        assertEquals(1, relogins)
        assertNull(tokens.refreshToken)
    }

    @Test
    fun `a token two 5xx answered goes out no more and the app signs in again`() = runTest {
        val manager = manager(
            Reply.Status(502), Reply.Status(503), Reply.Pair("access-2", "refresh-2"),
            reloginSucceeds = true,
        )
        assertNull(manager.authHeader())
        clock += 60_000
        assertNull(manager.authHeader())
        clock += 60_000

        assertEquals("Bearer access-relogin", manager.authHeader())

        assertEquals(listOf("refresh-1", "refresh-1"), sentRefreshTokens)
        assertEquals(1, relogins)
        assertEquals(0, tokens.refreshTokenSends)
    }

    @Test
    fun `the sends counted survive a restart`() = runTest {
        assertNull(manager(Reply.Status(504)).authHeader())

        // The app starts again: a new manager over the same store, with no pause in memory.
        val restarted = manager(Reply.Lost, Reply.Pair("access-2", "refresh-2"))

        assertNull(restarted.authHeader())
        assertEquals(listOf("refresh-1", "refresh-1"), sentRefreshTokens)
        assertEquals(1, relogins)
    }

    @Test
    fun `a send is counted on disk before it goes out`() = runTest {
        // So a process killed while it is out leaves it counted.
        var countedWhileOut = -1
        onRefreshSent = { countedWhileOut = tokens.refreshTokenSends }
        val manager = manager(Reply.Pair("access-2", "refresh-2"))

        assertEquals("Bearer access-2", manager.authHeader())

        assertEquals(1, countedWhileOut)
        // A new pair starts the count over.
        assertEquals(0, tokens.refreshTokenSends)
    }

    @Test
    fun `a 429 counts as no send`() = runTest {
        // The backend's limiter answers before the refresh runs.
        val manager = manager(Reply.Status(429), Reply.Lost, Reply.Pair("access-2", "refresh-2"))
        assertNull(manager.authHeader())
        clock += 60_000

        assertEquals("Bearer access-2", manager.authHeader())
        assertEquals(listOf("refresh-1", "refresh-1", "refresh-1"), sentRefreshTokens)
    }

    @Test
    fun `a new pair starts the count over`() = runTest {
        val manager = manager(
            Reply.Status(504), Reply.Pair("access-2", "refresh-2"),
            Reply.Lost, Reply.Pair("access-3", "refresh-3"),
        )
        assertNull(manager.authHeader())
        clock += 60_000
        assertEquals("Bearer access-2", manager.authHeader())
        clock += 900_000

        assertEquals("Bearer access-3", manager.authHeader())
        assertEquals(listOf("refresh-1", "refresh-1", "refresh-2", "refresh-2"), sentRefreshTokens)
    }

    @Test
    fun `a store written before the count reads none`() {
        // storePair writes the three keys an older version stores, and no count.
        assertEquals(0, tokens.refreshTokenSends)
    }

    @Test
    fun `a 429 or a 5xx keeps the pair without signing in again`() = runTest {
        for (code in listOf(429, 500, 502, 503, 504)) {
            storePair(expiresAt = 0L)
            val manager = manager(Reply.Status(code))

            assertNull("HTTP $code", manager.authHeader())

            assertEquals("HTTP $code", listOf("refresh-1"), sentRefreshTokens)
            assertEquals("HTTP $code", 0, relogins)
            assertEquals("HTTP $code", "access-1", tokens.accessToken)
            assertEquals("HTTP $code", "refresh-1", tokens.refreshToken)
        }
    }

    @Test
    fun `a 429 in the access token's last 30 s gives no header`() = runTest {
        // A token about to expire would draw a 401, and Home answers a 401 with a relogin.
        storePair(expiresAt = clock + 10_000)
        val manager = manager(Reply.Status(429))

        assertNull(manager.authHeader())
        assertNull(manager.authHeader())

        assertEquals(listOf("refresh-1"), sentRefreshTokens)
        assertEquals("refresh-1", tokens.refreshToken)
    }

    @Test
    fun `after a 429 or a 5xx the next refresh waits a minute and sends the kept token`() = runTest {
        val manager = manager(Reply.Status(503), Reply.Pair("access-2", "refresh-2"))
        assertNull(manager.authHeader())

        clock += 59_000
        assertNull(manager.authHeader())
        assertEquals(listOf("refresh-1"), sentRefreshTokens)

        clock += 1_000
        assertEquals("Bearer access-2", manager.authHeader())
        assertEquals(listOf("refresh-1", "refresh-1"), sentRefreshTokens)
        assertEquals("refresh-2", tokens.refreshToken)
    }

    @Test
    fun `a clock set back does not stretch the wait`() = runTest {
        val manager = manager(Reply.Status(429), Reply.Pair("access-2", "refresh-2"))
        assertNull(manager.authHeader())

        clock -= 3_600_000

        assertEquals("Bearer access-2", manager.authHeader())
    }

    @Test
    fun `a 401 signs in again as before`() = runTest {
        val manager = manager(Reply.Status(401), reloginSucceeds = true)

        assertEquals("Bearer access-relogin", manager.authHeader())

        assertEquals(listOf("refresh-1"), sentRefreshTokens)
        assertEquals(1, relogins)
    }

    @Test
    fun `a 401 whose sign-in fails drops the pair in one commit`() = runTest {
        val manager = manager(Reply.Status(401))

        assertNull(manager.authHeader())

        assertEquals(1, relogins)
        assertNull(tokens.refreshToken)
        // The send counted first; then the clear, of the issuer and student with the pair.
        assertEquals(listOf(setOf(KEY_REFRESH_TOKEN_SENDS), PAIR_KEYS + KEY_ISSUED_BY + KEY_STUDENT_ID), file.commits)
        assertEquals(0, file.applies)
    }

    @Test
    fun `a caller cancelled during the resend still signs in again`() = runTest {
        val manager = manager(Reply.Lost, Reply.Lost)
        lateinit var caller: Job
        onRefreshSent = { if (sentRefreshTokens.size == 2) caller.cancel() }

        caller = launch { manager.authHeader() }
        caller.join()

        // Left on disk, the token would go out a third time on the next refresh.
        assertEquals(1, relogins)
        assertNull(tokens.refreshToken)
    }

    @Test
    fun `a caller cancelled during the sign-in after a failed resend still drops the token`() = runTest {
        val manager = manager(Reply.Lost, Reply.Lost)
        lateinit var caller: Job
        manager.signInWithStoredAccount = {
            relogins++
            caller.cancel()
            delay(1_000)
            SignInAttempt.FAILED
        }

        caller = launch { manager.authHeader() }
        caller.join()

        assertEquals(1, relogins)
        assertNull(tokens.refreshToken)
    }

    @Test
    fun `OkHttp does not send a refresh again on its own`() = runTest {
        // The sign-in leaves its connection in the pool, and the refresh goes out on it. OkHttp
        // resends a request that failed on a pooled connection unless told not to.
        LoopbackBackend(listOf(LoopbackReply(pairJson("access-2", "refresh-2", expiresIn = 0)))).use { backend ->
            val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = backend.baseUrl }
            val manager = AuthTokenManager(tokens, OkHttpClient(), prefs, "device-1", now = { clock })
            manager.login("B00000000", moodleToken = "moodle", moodlePrivateToken = null)

            assertNull(manager.authHeader())

            assertEquals(listOf("refresh-2", "refresh-2"), backend.refreshTokens)
        }
    }

    @Test
    fun `OkHttp does not send a refresh again for a 503 that asks for it at once`() = runTest {
        // OkHttp follows a 503 with Retry-After: 0 by sending the request again,
        // whatever retryOnConnectionFailure says.
        val unavailable = LoopbackReply("""{"detail":"error"}""", status = 503, headers = listOf("Retry-After: 0"))
        LoopbackBackend(listOf(unavailable, LoopbackReply(pairJson("access-2", "refresh-2")))).use { backend ->
            val manager = networkManager(backend.baseUrl)

            assertNull(manager.authHeader())

            assertEquals(listOf("refresh-1"), backend.refreshTokens)
            assertEquals("refresh-1", tokens.refreshToken)
            assertEquals(1, tokens.refreshTokenSends)
        }
    }

    @Test
    fun `a refresh reply that comes back after a sign-out stores nothing`() = runTest {
        val manager = manager(Reply.Pair("access-2", "refresh-2"))
        onRefreshSent = { manager.logout() }

        assertNull(manager.authHeader())

        assertNull(tokens.accessToken)
        assertNull(tokens.refreshToken)
        // The send's count, the sign-out's clear (of the issuer and student too), and no save after it.
        assertEquals(listOf(setOf(KEY_REFRESH_TOKEN_SENDS), PAIR_KEYS + KEY_ISSUED_BY + KEY_STUDENT_ID), file.commits)
        assertEquals(0, relogins)
    }

    // A sign-in ends the device's other sessions (tigerduck-backend#213, #594)

    /**
     * The backend's login ends the account's other sessions on this device, so a refresh of the
     * pair a sign-in has just replaced answers for a session that is gone or going: its late pair
     * must not be stored over the sign-in's.
     */
    @Test
    fun `a refresh still out when a sign-in stores a pair does not store its late pair over it`() = runBlocking {
        val manager = manager(Reply.Pair("access-login", "refresh-login"), Reply.Pair("access-2", "refresh-2"))
        onRefreshSent = {
            onRefreshSent = {}
            runBlocking { manager.login("B00000000", moodleToken = "moodle", moodlePrivateToken = null) }
        }

        val header = manager.authHeader()

        assertEquals("refresh-login", tokens.refreshToken)
        assertEquals("Bearer access-login", header)
        assertEquals(0, relogins)
    }

    /** Its late 401 is the replaced session's: no relogin, which would end the sign-in's, and no clear. */
    @Test
    fun `a refresh turned down after a sign-in stored a pair leaves that pair alone`() = runBlocking {
        val manager = manager(Reply.Pair("access-login", "refresh-login"), Reply.Status(401))
        onRefreshSent = {
            onRefreshSent = {}
            runBlocking { manager.login("B00000000", moodleToken = "moodle", moodlePrivateToken = null) }
        }

        val header = manager.authHeader()

        assertEquals("refresh-login", tokens.refreshToken)
        assertEquals("Bearer access-login", header)
        assertEquals(0, relogins)
    }

    /** One login at a time, so the pair stored last is the one the backend issued last. */
    @Test
    fun `a second sign-in waits for the first to land before it sends`() = runBlocking {
        val manager = manager(Reply.Pair("access-a", "refresh-a"), Reply.Pair("access-b", "refresh-b"))
        val logins = AtomicInteger()
        val firstSent = CountDownLatch(1)
        val firstAnswer = CountDownLatch(1)
        onLoginSent = {
            if (logins.incrementAndGet() == 1) {
                firstSent.countDown()
                firstAnswer.await(5, TimeUnit.SECONDS)
            }
        }

        val first = async(Dispatchers.IO) { manager.login("B00000000", moodleToken = "moodle", moodlePrivateToken = null) }
        assertTrue(firstSent.await(5, TimeUnit.SECONDS))
        val second = async(Dispatchers.IO) { manager.login("B00000000", moodleToken = "moodle", moodlePrivateToken = null) }
        Thread.sleep(300)
        assertEquals("the second login waits", 1, logins.get())

        firstAnswer.countDown()
        first.await()
        second.await()
        assertEquals(2, logins.get())
        assertEquals("refresh-b", tokens.refreshToken)
    }

    /** A sign-in waiting its turn when the account signs out sends nothing. */
    @Test
    fun `a sign-in a sign-out overtook in the queue sends nothing`() = runBlocking {
        val manager = manager(Reply.Pair("access-a", "refresh-a"), Reply.Pair("access-b", "refresh-b"))
        val logins = AtomicInteger()
        val firstSent = CountDownLatch(1)
        val firstAnswer = CountDownLatch(1)
        onLoginSent = {
            if (logins.incrementAndGet() == 1) {
                firstSent.countDown()
                firstAnswer.await(5, TimeUnit.SECONDS)
            }
        }
        val signIn = manager.signInGeneration

        val first = async(Dispatchers.IO) {
            runCatching { manager.login("B00000000", moodleToken = "moodle", moodlePrivateToken = null, signInGeneration = signIn) }
        }
        assertTrue(firstSent.await(5, TimeUnit.SECONDS))
        val second = async(Dispatchers.IO) {
            runCatching { manager.login("B00000000", moodleToken = "moodle", moodlePrivateToken = null, signInGeneration = signIn) }
        }
        Thread.sleep(300)
        manager.logout()
        firstAnswer.countDown()

        first.await()
        assertTrue(second.await().exceptionOrNull() is AuthTokenManager.AuthException)
        assertEquals(1, logins.get())
        assertNull(tokens.refreshToken)
    }

    /**
     * A request refused with the pair a relogin has since replaced: signing in again would end
     * the relogin's session, and with steady traffic every refused request would end the last
     * one's. The caller retries with the stored pair instead.
     */
    @Test
    fun `signing in again goes only when the stored access token is the one refused`() = runBlocking {
        storePair(expiresAt = clock + 900_000)
        val manager = manager()

        assertTrue(manager.signInAgain())
        assertEquals(0, relogins)

        manager.noteRefused("access-1")
        manager.signInAgain()
        assertEquals(1, relogins)
    }

    /**
     * A relogin after a refusal whose pair a refresh replaced while it renewed the Moodle token:
     * at its turn the login is moot, and sending it would end the refresh's session. It sends
     * nothing, counts as signed in with the stored pair, and starts no pause.
     */
    @Test
    fun `a relogin whose pair was replaced before its login sends nothing`() = runBlocking {
        storePair(expiresAt = clock + 900_000)
        val manager = manager(Reply.Pair("access-x", "refresh-x"))
        val logins = AtomicInteger()
        onLoginSent = { logins.incrementAndGet() }
        manager.signInWithStoredAccount = { generation ->
            // A refresh lands while the Moodle token is renewed.
            tokens.save("access-2", "refresh-2", clock + 900_000)
            runCatching {
                manager.login("B00000000", moodleToken = "moodle", moodlePrivateToken = null, signInGeneration = generation)
            }.fold({ SignInAttempt.SIGNED_IN }, { SignInAttempt.FAILED })
        }
        manager.noteRefused("access-1")

        assertTrue(manager.signInAgain())

        assertEquals(0, logins.get())
        assertEquals("refresh-2", tokens.refreshToken)
        assertEquals(false, manager.lastSignInFailed.value)
    }

    /** Refusals of older pairs, however many, do not hide that the stored one was refused. */
    @Test
    fun `refusals of older tokens do not hide the stored one's`() = runBlocking {
        storePair(expiresAt = clock + 900_000)
        val manager = manager()
        manager.noteRefused("access-1")
        repeat(10) { manager.noteRefused("access-old-$it") }

        manager.signInAgain()

        assertEquals(1, relogins)
    }

    /** The backend clients' guard notes every access token a backend answers 401, for signInAgain. */
    @Test
    fun `the session guard notes an access token the backend refused`() = runBlocking {
        storePair(expiresAt = clock + 900_000)
        val manager = manager()
        val client = OkHttpClient.Builder()
            .guardedBy(manager)
            .addInterceptor { chain -> answer(chain.request(), 401, """{"detail":"session_revoked"}""") }
            .build()
        val url = resolveAnnouncementEndpoint(AppPreferences(context)).url + "/sync/revision"
        client.newCall(Request.Builder().url(url).header("Authorization", "Bearer access-1").build()).execute().close()

        manager.signInAgain()

        assertEquals(1, relogins)
    }

    @Test
    fun `a sign-in reply that comes back after a sign-out stores nothing`() = runTest {
        val manager = manager(Reply.Pair("access-2", "refresh-2"))
        onLoginSent = { manager.logout() }

        val failure = runCatching {
            manager.login("B00000000", moodleToken = "moodle", moodlePrivateToken = null)
        }.exceptionOrNull()

        assertTrue(failure is AuthTokenManager.AuthException)
        assertNull(tokens.refreshToken)
        assertEquals(1, file.commits.size)
    }

    @Test
    fun `a sign-in that read the credentials before a sign-out stores nothing`() = runTest {
        val manager = manager(Reply.Pair("access-2", "refresh-2"))
        // Read as AuthService reads it, before the credentials and the Moodle harvest.
        val signIn = manager.signInGeneration
        manager.logout()

        val failure = runCatching {
            manager.login(
                "B00000000", moodleToken = "moodle", moodlePrivateToken = null,
                signInGeneration = signIn,
            )
        }.exceptionOrNull()

        assertTrue(failure is AuthTokenManager.AuthException)
        assertNull(tokens.refreshToken)
    }

    @Test
    fun `a lost reply is not sent again after a sign-out`() = runTest {
        val manager = manager(Reply.Lost, Reply.Pair("access-2", "refresh-2"))
        onRefreshSent = { manager.logout() }

        assertNull(manager.authHeader())

        assertEquals(listOf("refresh-1"), sentRefreshTokens)
        assertEquals(0, relogins)
        assertNull(tokens.refreshToken)
    }

    @Test
    fun `a refresh turned down after a sign-out leaves the next sign-in alone`() = runTest {
        val manager = manager(Reply.Status(401))
        onRefreshSent = {
            manager.logout()
            // The next account signs in while the refresh is out.
            tokens.save("access-b", "refresh-b", clock + 900_000)
        }

        assertNull(manager.authHeader())

        assertEquals(0, relogins)
        assertEquals("refresh-b", tokens.refreshToken)
    }

    @Test
    fun `a sign-out during the sign-in after a refused refresh leaves the next account alone`() = runTest {
        for (signedIn in listOf(false, true)) {
            storePair(expiresAt = 0L)
            val manager = manager(Reply.Status(401))
            manager.signInWithStoredAccount = {
                relogins++
                manager.logout()
                // The next account signs in before this sign-in returns.
                tokens.save("access-b", "refresh-b", clock + 900_000)
                if (signedIn) SignInAttempt.SIGNED_IN else SignInAttempt.FAILED
            }

            // Neither its token for the old caller nor a clear of its pair.
            assertNull("signed in: $signedIn", manager.authHeader())

            assertEquals("signed in: $signedIn", 1, relogins)
            assertEquals("signed in: $signedIn", "refresh-b", tokens.refreshToken)
        }
    }

    @Test
    fun `an unknown host keeps the pair and pauses refreshes`() = runTest {
        // What the system resolver raises offline; OkHttp passes it on as it is.
        val manager = networkManager("http://backend.localhost/v3") {
            dns { host -> throw UnknownHostException(host) }
        }

        assertNull(manager.authHeader())
        clock += 59_000
        assertNull(manager.authHeader())

        assertEquals(1, calls.get())
        assertEquals(0, relogins)
        assertEquals("access-1", tokens.accessToken)
        assertEquals("refresh-1", tokens.refreshToken)
    }

    @Test
    fun `a refused connection keeps the pair and pauses refreshes`() = runTest {
        // Closed again at once, so nothing listens on the port and the connect is refused.
        val port = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { it.localPort }
        val manager = networkManager("http://127.0.0.1:$port/v3")

        assertNull(manager.authHeader())
        clock += 59_000
        assertNull(manager.authHeader())

        assertEquals(1, calls.get())
        assertEquals(0, relogins)
        assertEquals("access-1", tokens.accessToken)
        assertEquals("refresh-1", tokens.refreshToken)
    }

    @Test
    fun `no route to the host keeps the pair and pauses refreshes`() = runTest {
        val manager = networkManager("http://127.0.0.1:9/v3") {
            socketFactory(FailingConnectSocketFactory { NoRouteToHostException("Host unreachable") })
        }

        assertNull(manager.authHeader())
        clock += 59_000
        assertNull(manager.authHeader())

        assertEquals(1, calls.get())
        assertEquals(0, relogins)
        assertEquals("access-1", tokens.accessToken)
        assertEquals("refresh-1", tokens.refreshToken)
    }

    @Test
    fun `a connect that times out keeps the pair uncounted and pauses refreshes`() = runTest {
        // What a connect to a host that drops the packets raises once its timeout passes,
        // the same exception as a read timeout after the token went out.
        val manager = networkManager("http://127.0.0.1:9/v3") {
            socketFactory(FailingConnectSocketFactory { SocketTimeoutException("failed to connect") })
        }

        assertNull(manager.authHeader())
        clock += 59_000
        assertNull(manager.authHeader())

        assertEquals(1, calls.get())
        assertEquals(0, relogins)
        assertEquals("refresh-1", tokens.refreshToken)
        assertEquals(0, tokens.refreshTokenSends)
    }

    @Test
    fun `a refresh that never left the device counts as no send`() = runTest {
        val manager = networkManager("http://backend.localhost/v3") {
            dns { host -> throw UnknownHostException(host) }
        }

        assertNull(manager.authHeader())

        assertEquals("refresh-1", tokens.refreshToken)
        assertEquals(0, tokens.refreshTokenSends)
    }

    @Test
    fun `a refresh that times out after it went out is sent once more`() = runTest {
        LoopbackBackend(emptyList(), holdUnanswered = true).use { backend ->
            val manager = networkManager(backend.baseUrl) { readTimeout(200, TimeUnit.MILLISECONDS) }

            assertNull(manager.authHeader())

            // The first send may have rotated the token: one resend, then a sign-in as before.
            assertEquals(listOf("refresh-1", "refresh-1"), backend.refreshTokens)
            assertEquals(1, relogins)
            assertNull(tokens.refreshToken)
        }
    }

    @Test
    fun `a sign-in answer trickled past its deadline fails and lets the caller's lock go`() = runBlocking {
        // Each byte comes inside the read timeout, so only a deadline for the whole call ends it.
        val trickled = LoopbackReply(pairJson("access-2", "refresh-2").padEnd(150), trickleMs = 60)
        LoopbackBackend(listOf(LoopbackReply("""{"detail":"invalid"}""", status = 401), trickled)).use { backend ->
            val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = backend.baseUrl }
            // Never the real backend.
            assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
            val manager = AuthTokenManager(
                tokens, OkHttpClient(), prefs, "device-1", now = { clock }, signInCallTimeoutMs = 500,
            ).apply {
                // As StoredAccountSignIn signs in after the refused refresh.
                signInWithStoredAccount = { signIn ->
                    relogins++
                    runCatching { login("B00000000", "moodle", null, signInGeneration = signIn) }
                        .fold({ SignInAttempt.SIGNED_IN }, { SignInAttempt.FAILED })
                }
            }
            // Held while the header is fetched, as the course outbox holds its send lock.
            val sendLock = Mutex(locked = true)
            var header: String? = "none yet"
            launch(Dispatchers.IO) {
                try {
                    header = manager.authHeader()
                } finally {
                    sendLock.unlock()
                }
            }

            // A course delete waiting on that lock goes on long before the answer would end.
            withTimeout(3_000) { sendLock.withLock {} }

            assertNull(header)
            assertEquals(1, relogins)
            assertNull(tokens.refreshToken)
        }
    }

    /**
     * A manager whose refreshes go through OkHttp's own DNS lookup, connect and
     * exchange, so a test meets the exception OkHttp raises itself.
     */
    private fun networkManager(baseUrl: String, configure: OkHttpClient.Builder.() -> Unit = {}): AuthTokenManager {
        val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = baseUrl }
        // Never the real backend.
        assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        val client = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) {
                    calls.incrementAndGet()
                }
            })
            .apply(configure)
            .build()
        return AuthTokenManager(tokens, client, prefs, "device-1", now = { clock }).apply {
            signInWithStoredAccount = {
                relogins++
                SignInAttempt.FAILED
            }
        }
    }

    // The sign-out's unregister refreshes the departing session, which is never stored

    private fun departing() = DepartingSession("access-1", "refresh-1", 0L, "http://127.0.0.1:9/v3")

    @Test
    fun `a departing refresh whose reply was lost says so, and the next send takes the pair`() = runTest {
        val manager = manager(Reply.Lost, Reply.Pair("access-2", "refresh-2"))

        assertEquals(DepartingRefresh.NoReply, manager.refreshDeparting(departing(), sendsWithoutReply = 0))
        val reply = manager.refreshDeparting(departing(), sendsWithoutReply = 1)

        assertEquals("refresh-2", (reply as DepartingRefresh.Refreshed).session.refreshToken)
        assertEquals(listOf("refresh-1", "refresh-1"), sentRefreshTokens)
    }

    @Test
    fun `a departing token gone twice without a reply is never sent a third time`() = runTest {
        // Within its grace the backend answers a rotated token once more; any later send of it is
        // taken for reuse, which revokes the session and leaves the unregister nothing to go out as.
        val manager = manager(Reply.Pair("access-3", "refresh-3"))

        // Two counted with the debt, by a process that has ended since.
        assertEquals(DepartingRefresh.Refused, manager.refreshDeparting(departing(), sendsWithoutReply = 2))
        assertEquals(emptyList<String>(), sentRefreshTokens)
    }

    @Test
    fun `a departing refresh the backend did not take is left for a later try`() = runTest {
        val manager = manager(Reply.Status(503))

        assertEquals(DepartingRefresh.Unavailable, manager.refreshDeparting(departing(), sendsWithoutReply = 0))
    }

    @Test
    fun `a departing refresh turned away or never sent did not use the token, and says so`() = runTest {
        // A 429 comes from the limiter before the refresh runs; a connect refused never left the
        // phone. Neither counts as a send, unlike a 5xx, which may follow the backend's rotation.
        assertEquals(DepartingRefresh.Unused, manager(Reply.Status(429)).refreshDeparting(departing(), sendsWithoutReply = 0))
        assertEquals(DepartingRefresh.Unused, manager(Reply.Unsent).refreshDeparting(departing(), sendsWithoutReply = 0))
    }

    @Test
    fun `the departing session carries every send of the token that may have been used, the one on its way too`() = runTest {
        val manager = manager(Reply.Lost, Reply.Lost)
        // Signed out as the resend goes, after the first send's reply was lost: either may have
        // reached the backend, and each is counted on disk before it goes out.
        var departing: DepartingSession? = null
        var sends = 0
        onRefreshSent = { if (++sends == 2) departing = manager.logout() }

        assertNull(manager.authHeader())

        assertEquals(2, departing?.sendsWithoutReply)
    }

    @Test
    fun `a sign-out between a refresh's start and the count of its send keeps the token in`() = runTest {
        // The refresh marks itself on its way, then counts its send on disk. Signed out between
        // the two, the session was taken with no send counted, so this one must not go out: a
        // 5xx would leave nothing in this process to count it, and the unregister could send the
        // token twice more, the second time as its third.
        val manager = manager(Reply.Status(503), prefs = endpointReadPrefs())
        var departing: DepartingSession? = null
        onEndpointRead = {
            onEndpointRead = {}
            departing = manager.logout()
        }

        assertNull(manager.authHeader())

        assertEquals("nothing went out as the account signed out", emptyList<String>(), sentRefreshTokens)
        assertEquals(0, departing?.sendsWithoutReply)
        assertEquals(0, relogins)
        // The unregister's refresh is the token's first send.
        assertEquals(DepartingRefresh.Unavailable, manager.refreshDeparting(departing!!, sendsWithoutReply = 0))
        assertEquals(listOf("refresh-1"), sentRefreshTokens)
    }

    @Test
    fun `a send answered with a 5xx and one on its way at sign-out are both counted, so the unregister never sends a third`() = runTest {
        val manager = manager(Reply.Status(503), Reply.Lost, Reply.Pair("access-3", "refresh-3"))
        // A 5xx may come from a gateway after the backend rotated: the send stays counted.
        assertNull(manager.authHeader())
        assertEquals(1, tokens.refreshTokenSends)
        clock += 60_000
        // The next refresh is on its way as the account signs out, and its reply is lost.
        var departing: DepartingSession? = null
        onRefreshSent = { departing = manager.logout() }
        assertNull(manager.authHeader())

        assertEquals(2, departing?.sendsWithoutReply)
        assertEquals(
            DepartingRefresh.Refused,
            manager.refreshDeparting(departing!!, sendsWithoutReply = departing!!.sendsWithoutReply),
        )
        assertEquals(listOf("refresh-1", "refresh-1"), sentRefreshTokens)
    }

    @Test
    fun `a sign-out hands back the session it wipes, with a refresh that landed before it`() = runTest {
        val manager = manager(Reply.Pair("access-2", "refresh-2"))
        assertEquals("Bearer access-2", manager.authHeader())

        val departing = manager.logout()

        assertEquals("refresh-2", departing?.refreshToken)
        assertNull(tokens.refreshToken)
        assertNull("nothing to hand back the second time", manager.logout())
    }

    // A session refresh still on its way at sign-out, with the same token as the departing one

    @Test
    fun `a departing refresh takes the pair of a refresh on its way at sign-out instead of sending again`() = runTest {
        val manager = manager(Reply.Pair("access-2", "refresh-2"), Reply.Pair("access-3", "refresh-3"))
        // As AuthService.logout() takes it: while the session's refresh is on its way.
        var departing: DepartingSession? = null
        onRefreshSent = {
            departing = manager.departingSession()
            manager.logout()
            onRefreshSent = {}
        }
        assertNull(manager.authHeader())

        val reply = manager.refreshDeparting(departing!!, sendsWithoutReply = 0)

        assertEquals("refresh-2", (reply as DepartingRefresh.Refreshed).session.refreshToken)
        assertEquals(listOf("refresh-1"), sentRefreshTokens)
        assertNull("still nothing stored", tokens.refreshToken)
    }

    @Test
    fun `a departing refresh waits for the session refresh of its token still on its way`() = runBlocking {
        val manager = manager(Reply.Pair("access-2", "refresh-2"), Reply.Pair("access-3", "refresh-3"))
        val sent = CountDownLatch(1)
        val answer = CountDownLatch(1)
        var departing: DepartingSession? = null
        onRefreshSent = {
            departing = manager.departingSession()
            manager.logout()
            onRefreshSent = {}
            sent.countDown()
            answer.await(5, TimeUnit.SECONDS)
        }
        val sessionRefresh = async(Dispatchers.IO) { manager.authHeader() }
        assertTrue(sent.await(5, TimeUnit.SECONDS))

        val unregisterRefresh = async(Dispatchers.IO) { manager.refreshDeparting(departing!!, sendsWithoutReply = 0) }
        Thread.sleep(300)
        answer.countDown()

        assertNull(sessionRefresh.await())
        assertEquals("refresh-2", (unregisterRefresh.await() as DepartingRefresh.Refreshed).session.refreshToken)
        assertEquals(listOf("refresh-1"), sentRefreshTokens)
    }

    @Test
    fun `a departing refresh waits for the session refresh on its way only so long`() = runBlocking {
        val manager = manager(Reply.Pair("access-2", "refresh-2"), departingWaitMs = 200)
        val sent = CountDownLatch(1)
        val answer = CountDownLatch(1)
        var departing: DepartingSession? = null
        onRefreshSent = {
            departing = manager.departingSession()
            manager.logout()
            onRefreshSent = {}
            sent.countDown()
            answer.await(5, TimeUnit.SECONDS)
        }
        val sessionRefresh = async(Dispatchers.IO) { manager.authHeader() }
        assertTrue(sent.await(5, TimeUnit.SECONDS))

        // Left for a later try, rather than holding up whoever waits on the unregister.
        assertEquals(DepartingRefresh.Unavailable, manager.refreshDeparting(departing!!, sendsWithoutReply = 0))
        assertEquals(listOf("refresh-1"), sentRefreshTokens)

        answer.countDown()
        sessionRefresh.await()
        Unit
    }

    @Test
    fun `a token the session sent twice without a reply is not sent a third time for the unregister`() = runTest {
        val manager = manager(Reply.Lost, Reply.Lost, Reply.Pair("access-3", "refresh-3"))
        val departing = manager.departingSession()!!
        // The resend's reply is lost as the account signs out.
        var sends = 0
        onRefreshSent = { if (++sends == 2) manager.logout() }
        assertNull(manager.authHeader())

        assertEquals(DepartingRefresh.Refused, manager.refreshDeparting(departing, sendsWithoutReply = 0))
        assertEquals(listOf("refresh-1", "refresh-1"), sentRefreshTokens)
    }

    private fun manager(
        vararg replies: Reply,
        reloginSucceeds: Boolean = false,
        departingWaitMs: Long = 20_000,
        prefs: AppPreferences = AppPreferences(context),
    ) = AuthTokenManager(
        tokens = tokens,
        httpClient = backend(*replies),
        prefs = prefs,
        deviceUuid = "device-1",
        now = { clock },
        departingWaitMs = departingWaitMs,
    ).apply {
        // Stands in for AuthService's automatic sign-in, which signs in through login().
        signInWithStoredAccount = {
            relogins++
            if (reloginSucceeds) tokens.save("access-relogin", "refresh-relogin", clock + 900_000)
            if (reloginSucceeds) SignInAttempt.SIGNED_IN else SignInAttempt.FAILED
        }
    }

    /** What the fake backend does with each request it gets, in order. */
    private sealed interface Reply {
        /** A new pair, as a rotation or a sign-in answers. */
        data class Pair(val access: String, val refresh: String) : Reply

        /** No reply reaches the phone, whether or not the backend rotated. */
        data object Lost : Reply

        /** The request never leaves the phone: the connect is refused. */
        data object Unsent : Reply

        data class Status(val code: Int) : Reply
    }

    // Answers in place of the network: the interceptor never calls proceed().
    private fun backend(vararg replies: Reply): OkHttpClient {
        val script = ArrayDeque(replies.asList())
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                // Before the fake backend sees the request, as OkHttp raises it.
                if (script.first() == Reply.Unsent) {
                    script.removeFirst()
                    throw ConnectException("Failed to connect")
                }
                if (request.url.encodedPath.endsWith("/auth/refresh")) {
                    val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                    sentRefreshTokens += JSONObject(body).getString("refresh_token")
                    onRefreshSent()
                } else if (request.url.encodedPath.endsWith("/auth/login")) {
                    onLoginSent()
                }
                when (val reply = script.removeFirst()) {
                    Reply.Lost -> throw SocketTimeoutException("timeout")
                    Reply.Unsent -> error("raised above")
                    is Reply.Status -> answer(request, reply.code, """{"detail":"error"}""")
                    is Reply.Pair -> answer(request, 200, pairJson(reply.access, reply.refresh))
                }
            }
            .build()
    }

    private fun answer(request: Request, code: Int, body: String) = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()
}

/** What saving or dropping a pair writes, in one commit. */
private val PAIR_KEYS = setOf(KEY_ACCESS_TOKEN, KEY_REFRESH_TOKEN, KEY_EXPIRES_AT, KEY_REFRESH_TOKEN_SENDS)

private fun pairJson(access: String, refresh: String, expiresIn: Int = 900) =
    """{"access_token":"$access","refresh_token":"$refresh","expires_in":$expiresIn,""" +
        """"device_id":"device-1"}"""

/**
 * One answer of [LoopbackBackend]: [body] with [status] and any further [headers], each of its
 * bytes [trickleMs] after the last.
 */
private class LoopbackReply(
    val body: String,
    val status: Int = 200,
    val trickleMs: Long = 0,
    val headers: List<String> = emptyList(),
)

/**
 * A plain-HTTP backend on the loopback address that answers each request with
 * the next of [replies]. Once they run out it closes the connection without a
 * reply, or with [holdUnanswered] keeps it open and never answers. It keeps a
 * connection open after each answer, so OkHttp pools it.
 */
private class LoopbackBackend(
    private val replies: List<LoopbackReply>,
    private val holdUnanswered: Boolean = false,
) : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val answered = AtomicInteger()

    /** The refresh tokens it received, in order. */
    val refreshTokens = CopyOnWriteArrayList<String>()
    val baseUrl = "http://127.0.0.1:${server.localPort}/v3"

    init {
        thread(isDaemon = true) {
            while (true) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                // A client that timed out may reset the connection under the read.
                thread(isDaemon = true) { runCatching { serve(socket) } }
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.use {
            // Latin-1 maps each byte to one char, so Content-Length counts chars too.
            val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val output = socket.getOutputStream()
            while (true) {
                val path = input.readLine()?.split(' ')?.getOrNull(1) ?: return
                var length = 0
                while (true) {
                    val header = input.readLine() ?: return
                    if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", ignoreCase = true)) {
                        length = header.substringAfter(':').trim().toInt()
                    }
                }
                val body = CharArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n < 0) return
                    read += n
                }
                if (path.endsWith("/auth/refresh")) {
                    refreshTokens += JSONObject(String(body)).getString("refresh_token")
                }
                val reply = replies.getOrNull(answered.getAndIncrement())
                    ?: if (holdUnanswered) continue else return
                val bytes = reply.body.toByteArray()
                output.write(
                    ("HTTP/1.1 ${reply.status} Answer\r\nContent-Type: application/json\r\n" +
                        reply.headers.joinToString("") { "$it\r\n" } +
                        "Content-Length: ${bytes.size}\r\n\r\n").toByteArray()
                )
                if (reply.trickleMs == 0L) output.write(bytes)
                output.flush()
                if (reply.trickleMs > 0) {
                    for (byte in bytes) {
                        Thread.sleep(reply.trickleMs)
                        output.write(byte.toInt())
                        output.flush()
                    }
                }
            }
        }
    }

    override fun close() = server.close()
}

/** Hands out sockets whose connect fails with [failure], as Android's does when the host is unreachable or silent. */
private class FailingConnectSocketFactory(private val failure: () -> IOException) : SocketFactory() {
    override fun createSocket(): Socket = object : Socket() {
        override fun connect(endpoint: SocketAddress?, timeout: Int): Unit = throw failure()
    }

    override fun createSocket(host: String?, port: Int): Socket = unconnectedOnly()
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        unconnectedOnly()
    override fun createSocket(host: InetAddress?, port: Int): Socket = unconnectedOnly()
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        unconnectedOnly()

    // OkHttp asks for an unconnected socket and connects it itself.
    private fun unconnectedOnly(): Nothing = throw UnsupportedOperationException()
}

/** Records the keys of each committed edit, and how many edits were applied instead. */
private class SavesRecorder(private val inner: SharedPreferences) : SharedPreferences by inner {
    val commits = mutableListOf<Set<String>>()
    var applies = 0

    /** Reports each commit that writes this key as failed, as a disk that refuses the write does, after the edit is in memory. */
    var failCommitsOf: String? = null

    /** How many commits of [failCommitsOf] go through before the disk starts refusing them. */
    var failCommitsAfter = 0
    private var commitsOfFailing = 0

    override fun edit(): SharedPreferences.Editor = Editor(inner.edit())

    private inner class Editor(private val editor: SharedPreferences.Editor) :
        SharedPreferences.Editor by editor {
        private val keys = mutableSetOf<String>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            keys += key
            editor.putString(key, value)
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            keys += key
            editor.putLong(key, value)
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            keys += key
            editor.putInt(key, value)
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            keys += key
            editor.remove(key)
            return this
        }

        override fun commit(): Boolean {
            commits += keys.toSet()
            val failing = failCommitsOf?.let { it in keys && ++commitsOfFailing > failCommitsAfter } ?: false
            return editor.commit() && !failing
        }

        override fun apply() {
            applies++
            editor.apply()
        }
    }
}
