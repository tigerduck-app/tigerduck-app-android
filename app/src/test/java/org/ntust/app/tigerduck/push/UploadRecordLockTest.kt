package org.ntust.app.tigerduck.push

import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.shared.account.SignIn
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.ntust.app.tigerduck.util.JvmContext

/**
 * The upload ledger's record and a sign-out exclude each other
 * ([SignIn.ifCurrent]): checking the sign-in first and recording after left a gap
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
        // The courses ledger's store as PushApiClient wires it.
        val store = object : UploadLedgerStore {
            override val fingerprint: String? get() = prefs.coursesUploadFingerprint("1151")
            override val acceptedAtMs: Long get() = prefs.coursesUploadedAtMs("1151")
        }
        val ledger = UploadLedger(store, System::currentTimeMillis)
        val signIn = tokenManager.session.signIns.current
        val upload = thread(name = "upload") {
            runBlocking {
                // As PushApiClient wires it, with a pause inside the record.
                ledger.sendIfDue("list-a", record = { fingerprint, atMs ->
                    signIn.ifCurrent {
                        recordUnderWay.countDown()
                        // Bounded, so a failed test cannot hang on it; the test lets it go well before.
                        if (!recordMayEnd.await(10, TimeUnit.SECONDS)) recordTimedOut.set(true)
                        prefs.recordCoursesUpload("1151", fingerprint, atMs)
                    }
                }, signIn = signIn) {}
            }
        }
        val signOut = thread(start = false, name = "sign-out") { tokenManager.logout() }
        try {
            assertTrue("the record never began", recordUnderWay.await(10, TimeUnit.SECONDS))
            signOut.start()
            // The sign-out comes to end the sign-in and waits, as the record under way holds it.
            // With the record made outside it, it runs through and ends instead.
            assertTrue("the sign-out did not wait for the record", reaches(signOut, Thread.State.BLOCKED))
        } finally {
            recordMayEnd.countDown()
        }
        upload.join(10_000)
        signOut.join(10_000)
        assertFalse("the upload did not end", upload.isAlive)
        assertFalse("the sign-out did not end", signOut.isAlive)
        assertFalse("the record waited its bound out", recordTimedOut.get())

        // AuthService.logout: the sign-in ended, after the record had landed...
        assertFalse(signIn.isCurrent)
        assertEquals("list-a", prefs.coursesUploadFingerprint("1151"))
        // ...and its clearing of the stamps, which follows, takes the record with it.
        prefs.clearSyncStamps()

        assertNull(prefs.coursesUploadFingerprint("1151"))
        assertTrue(runBlocking { ledger.sendIfDue("list-a", record = { _, _ -> }, signIn = signIn) {} })
    }
}
