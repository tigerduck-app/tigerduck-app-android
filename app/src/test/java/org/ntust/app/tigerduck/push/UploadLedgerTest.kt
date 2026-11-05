@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.push

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.util.testSignIn
import java.io.IOException

/**
 * The background worker posted the whole assignment list every 15 minutes,
 * and each upload pushed the server's own sync back two hours
 * (tigerduck-app-android#220). A list the server accepted goes again only
 * once it changes, or six hours on, as on the iPhone ([UploadLedger]).
 */
class UploadLedgerTest {

    private var now = 1_000_000L
    private var sends = 0

    private val store = object : UploadLedgerStore {
        override var fingerprint: String? = null
        override var acceptedAtMs = 0L
    }

    private fun record(fingerprint: String, atMs: Long) {
        store.fingerprint = fingerprint
        store.acceptedAtMs = atMs
    }

    private val ledger = UploadLedger(store) { now }
    private val standing = testSignIn()

    private fun fingerprint(list: String, backend: String = BACKEND) = uploadFingerprint(backend, list)

    private suspend fun upload(list: String, current: Boolean = true) =
        ledger.sendIfDue(fingerprint(list), { list, atMs -> if (current) record(list, atMs) }, signIn = standing) { sends++ }

    @Test
    fun `an unchanged list is not sent again until six hours on`() = runTest {
        assertTrue(upload("[a,b]"))
        now += 15 * 60_000L
        assertFalse(upload("[a,b]"))
        now += UNCHANGED_UPLOAD_INTERVAL_MS
        assertTrue(upload("[a,b]"))
        assertEquals(2, sends)
    }

    @Test
    fun `a changed list is sent at once`() = runTest {
        upload("[a,b]")
        now += 60_000L

        assertTrue(upload("[a,b,c]"))
        assertEquals(2, sends)
    }

    @Test
    fun `a failed upload is not recorded, so the next one goes`() = runTest {
        runCatching { ledger.sendIfDue(fingerprint("[a]"), ::record, signIn = standing) { throw IOException("offline") } }

        assertTrue(upload("[a]"))
    }

    @Test
    fun `a list the server turned away waits a minute before it goes again, another list not at all`() = runTest {
        runCatching { ledger.sendIfDue(fingerprint("[a]"), ::record, signIn = standing) { throw PushApiException("HTTP 500", 500) } }

        assertFalse(upload("[a]"))
        assertTrue(upload("[b]"))
        now += REFUSED_UPLOAD_PAUSE_MS - 1
        assertFalse(upload("[a]"))
        now += 1
        assertTrue(upload("[a]"))
    }

    private suspend fun refuse(list: String, signIn: SignIn = standing, takesMs: Long = 0) = runCatching {
        ledger.sendIfDue(fingerprint(list), ::record, signIn = signIn) {
            now += takesMs
            throw PushApiException("HTTP 500", 500)
        }
    }

    @Test
    fun `the minute runs from the refusal's answer, not from the send`() = runTest {
        refuse("[a]", takesMs = 55_000)

        now += 10_000

        assertFalse(upload("[a]"))
    }

    @Test
    fun `each list the server turned away waits its own minute`() = runTest {
        refuse("[a]")
        refuse("[b]")

        assertFalse(upload("[a]"))
        assertFalse(upload("[b]"))
    }

    @Test
    fun `a term reset forgets the refusals, so the list after it goes`() = runTest {
        refuse("[a]")

        ledger.holdingUploads { }

        assertTrue(upload("[a]"))
    }

    @Test
    fun `a refusal under one sign-in does not hold the next one's list`() = runTest {
        refuse("[a]", signIn = testSignIn())

        assertTrue(ledger.sendIfDue(fingerprint("[a]"), ::record, signIn = testSignIn()) { sends++ })
    }

    @Test
    fun `a list sent always goes after a refusal`() = runTest {
        runCatching { ledger.sendIfDue(fingerprint("[a]"), ::record, signIn = standing) { throw PushApiException("HTTP 500", 500) } }

        assertTrue(ledger.sendIfDue(fingerprint("[a]"), ::record, always = true, signIn = standing) { sends++ })
    }

    @Test
    fun `an upload that outlived a sign-out records nothing`() = runTest {
        upload("[a]", current = false)

        assertTrue(upload("[a]"))
    }

    @Test
    fun `the record is made inside the sign-in check, so a sign-out's clear cannot fall between them`() = runTest {
        // In the app the check is the sign-in's own (SignIn.ifCurrent), atomic with the sign-out;
        // the record it is handed is the whole of what the ledger writes, so nothing lands past it.
        var recordedInside = false
        ledger.sendIfDue(fingerprint("[a]"), { list, atMs -> record(list, atMs); recordedInside = store.fingerprint != null }, signIn = standing) { sends++ }

        assertTrue(recordedInside)
        assertFalse(upload("[a]"))
    }

    @Test
    fun `a forced upload goes whatever the ledger holds, and is recorded like any other`() = runTest {
        upload("[a]")

        assertTrue(ledger.sendIfDue(fingerprint("[a]"), ::record, always = true, signIn = standing) { sends++ })

        assertEquals(2, sends)
        assertFalse(upload("[a]"))
    }

    @Test
    fun `a term reset waits for the upload in flight, so the list it records is forgotten too`() = runTest {
        val answer = CompletableDeferred<Unit>()
        val inFlight = launch { ledger.sendIfDue(fingerprint("[a]"), ::record, signIn = standing) { answer.await() } }
        runCurrent()
        val reset = launch {
            ledger.holdingUploads {
                store.fingerprint = null
                store.acceptedAtMs = 0L
            }
        }
        runCurrent()
        assertFalse(reset.isCompleted)

        answer.complete(Unit)
        inFlight.join()
        reset.join()

        assertTrue(upload("[a]"))
    }

    @Test
    fun `the same courses in another order are one list, a changed course or another backend another`() {
        val cs1 = Course(courseNo = "CS1", courseName = "CS1")
        val cs2 = Course(courseNo = "CS2", courseName = "CS2")

        assertEquals(
            courseUploadFingerprint(BACKEND, listOf(cs1, cs2), "1151"),
            courseUploadFingerprint(BACKEND, listOf(cs2, cs1), "1151"),
        )
        assertNotEquals(
            courseUploadFingerprint(BACKEND, listOf(cs1, cs2), "1151"),
            courseUploadFingerprint(BACKEND, listOf(cs1.copy(courseName = "Calculus"), cs2), "1151"),
        )
        assertNotEquals(
            courseUploadFingerprint(BACKEND, listOf(cs1, cs2), "1151"),
            courseUploadFingerprint("https://dev.example/v3", listOf(cs1, cs2), "1151"),
        )
    }

    /**
     * The roster flag is part of the list (iOS `UnchangedUploadTests.rosterTermsGoUpAndCount`): a
     * term's courses sent once without it go again as its roster, unchanged or not.
     */
    @Test
    fun `the same courses as a term's roster are another list than without`() {
        val cs1 = Course(courseNo = "CS1", courseName = "CS1")

        assertNotEquals(
            courseUploadFingerprint(BACKEND, listOf(cs1), "1151"),
            courseUploadFingerprint(BACKEND, listOf(cs1), "1151", rosterTerms = listOf("1151")),
        )
        assertEquals(
            courseUploadFingerprint(BACKEND, listOf(cs1), "1151", rosterTerms = listOf("1151")),
            courseUploadFingerprint(BACKEND, listOf(cs1), "1151", rosterTerms = listOf("1151")),
        )
    }

    /**
     * A list the backend accepted from an earlier build is still the same list: the fingerprint
     * hashes the body the upload carried then, a picked color included, though no upload carries
     * one now. Otherwise every term would go again once more for this build's sake.
     */
    @Test
    fun `a term's fingerprint hashes its list as the build before the v4 forms did, colors included`() {
        val calculus = Course(courseNo = "CS1", courseName = "Calculus", moodleIdNumber = "1151CS1", customColorHex = "#FF0000")

        assertEquals(
            uploadFingerprint(
                BACKEND,
                """{"courses":[{"semester":"1151","course_no":"CS1","course_name":"Calculus","moodle_id":"1151CS1","credits":0.0,""" +
                    """"classroom":"","instructors":[],"schedule_json":{},"classroom_map":{}}],""" +
                    """"course_overrides":[{"course_key":"client:1151:CS1","color_hex":"#FF0000"}],"roster_terms":["1151"]}""",
            ),
            courseUploadFingerprint(BACKEND, listOf(calculus), "1151", rosterTerms = listOf("1151")),
        )
        assertNotEquals(
            courseUploadFingerprint(BACKEND, listOf(calculus), "1151"),
            courseUploadFingerprint(BACKEND, listOf(calculus.copy(customColorHex = "#00FF00")), "1151"),
        )
    }

    @Test
    fun `a clock moved back past the last upload sends again`() {
        assertTrue(uploadIsDue("x", acceptedAtMs = 5_000L, fingerprint = "x", nowMs = 4_000L))
        assertFalse(uploadIsDue("x", acceptedAtMs = 5_000L, fingerprint = "x", nowMs = 6_000L))
    }

    @Test
    fun `the same list to the same backend gives the same fingerprint, another list or backend another`() {
        assertEquals(fingerprint("[1,2]"), fingerprint("[1,2]"))
        assertNotEquals(fingerprint("[1,2]"), fingerprint("[1,3]"))
        assertNotEquals(fingerprint("[1,2]"), fingerprint("[1,2]", backend = "https://dev.example/v3"))
    }

    private companion object {
        const val BACKEND = "https://api.example/v3"
    }
}
