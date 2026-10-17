package org.ntust.app.tigerduck.push

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course
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
        override fun record(fingerprint: String, atMs: Long) {
            this.fingerprint = fingerprint
            acceptedAtMs = atMs
        }
    }

    private val ledger = UploadLedger(store) { now }

    private fun fingerprint(list: String, backend: String = BACKEND) = uploadFingerprint(backend, list)

    private suspend fun upload(list: String, current: Boolean = true) =
        ledger.sendIfDue(fingerprint(list), { record -> if (current) record() }) { sends++ }

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
        runCatching { ledger.sendIfDue(fingerprint("[a]"), { it() }) { throw IOException("offline") } }

        assertTrue(upload("[a]"))
    }

    @Test
    fun `an upload that outlived a sign-out records nothing`() = runTest {
        upload("[a]", current = false)

        assertTrue(upload("[a]"))
    }

    @Test
    fun `the record is made inside the sign-in check, so a sign-out's clear cannot fall between them`() = runTest {
        // In the app the check runs under the lock a sign-out moves the sign-in under
        // (AuthTokenManager.runIfSignedIn); the record it is handed is the whole of what the
        // ledger writes, so nothing lands outside that lock.
        var recordedInside = false
        ledger.sendIfDue(fingerprint("[a]"), { record -> record(); recordedInside = store.fingerprint != null }) { sends++ }

        assertTrue(recordedInside)
        assertFalse(upload("[a]"))
    }

    @Test
    fun `a forced upload goes whatever the ledger holds, and is recorded like any other`() = runTest {
        upload("[a]")

        assertTrue(ledger.sendIfDue(fingerprint("[a]"), { it() }, always = true) { sends++ })

        assertEquals(2, sends)
        assertFalse(upload("[a]"))
    }

    @Test
    fun `a term reset waits for the upload in flight, so the list it records is forgotten too`() = runTest {
        val answer = CompletableDeferred<Unit>()
        val inFlight = launch { ledger.sendIfDue(fingerprint("[a]"), { it() }) { answer.await() } }
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
