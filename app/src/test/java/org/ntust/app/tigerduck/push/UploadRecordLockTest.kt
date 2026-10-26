package org.ntust.app.tigerduck.push

import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.ntust.app.tigerduck.util.JvmContext

/**
 * The upload ledger's record and a sign-out exclude each other
 * ([AuthTokenManager.runIfSignedIn]): checking the sign-in first and recording after left a gap
 * the sign-out fell into, its clearing of the stamps landing between the two, and the departing
 * account's list stayed for the next account to skip.
 */
class UploadRecordLockTest {

    /** Whether [thread] reaches [state] within [timeoutMs]; false at once should it end instead. */
    private fun reaches(thread: Thread, state: Thread.State, timeoutMs: Long = 5_000): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            when (thread.state) {
                state -> return true
                Thread.State.TERMINATED -> return false
                else -> if (System.nanoTime() >= deadline) return false else Thread.sleep(2)
            }
        }
    }

    @Test
    fun `a sign-out waits for a record under way, and its clear then takes the record with it`() {
        val context = JvmContext()
        val prefs = AppPreferences(context)
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-upload-record-lock-test", Context.MODE_PRIVATE))
        tokens.save("access", "refresh", Long.MAX_VALUE)
        val tokenManager = AuthTokenManager(tokens, OkHttpClient(), prefs, "device-1")
        val recordUnderWay = CountDownLatch(1)
        val recordMayEnd = CountDownLatch(1)
        val recordTimedOut = AtomicBoolean(false)
        // The courses ledger's store as PushApiClient wires it, with a pause inside the record.
        val store = object : UploadLedgerStore {
            override val fingerprint: String? get() = prefs.coursesUploadFingerprint("1151")
            override val acceptedAtMs: Long get() = prefs.coursesUploadedAtMs("1151")
            override fun record(fingerprint: String, atMs: Long) {
                recordUnderWay.countDown()
                // Bounded, so a failed test cannot hang on it; the test lets it go well before.
                if (!recordMayEnd.await(10, TimeUnit.SECONDS)) recordTimedOut.set(true)
                prefs.recordCoursesUpload("1151", fingerprint, atMs)
            }
        }
        val ledger = UploadLedger(store, System::currentTimeMillis)
        // Read before the record holds the sign-in lock: the read takes that lock too, and under
        // the pause it would wait the record's bound out.
        val signIn = tokenManager.signInGeneration
        val upload = thread(name = "upload") {
            runBlocking {
                ledger.sendIfDue("list-a", ifCurrent = { record -> tokenManager.runIfSignedIn(signIn, record) }) {}
            }
        }
        val signOut = thread(start = false, name = "sign-out") { tokenManager.logout() }
        try {
            assertTrue("the record never began", recordUnderWay.await(10, TimeUnit.SECONDS))
            signOut.start()
            // The sign-out reaches the sign-in lock and waits on it, held by the record under way.
            // With the record made outside the lock, as before, it runs through and ends instead.
            assertTrue("the sign-out did not wait on the sign-in lock", reaches(signOut, Thread.State.BLOCKED))
        } finally {
            recordMayEnd.countDown()
        }
        upload.join(10_000)
        signOut.join(10_000)
        assertFalse("the upload did not end", upload.isAlive)
        assertFalse("the sign-out did not end", signOut.isAlive)
        assertFalse("the record waited its bound out", recordTimedOut.get())

        // AuthService.logout: the generation moved, after the record had landed...
        assertNotEquals(signIn, tokenManager.signInGeneration)
        assertEquals("list-a", prefs.coursesUploadFingerprint("1151"))
        // ...and its clearing of the stamps, which follows, takes the record with it.
        prefs.clearSyncStamps()

        assertNull(prefs.coursesUploadFingerprint("1151"))
        assertTrue(runBlocking { ledger.sendIfDue("list-a", ifCurrent = { it() }) {} })
    }
}
