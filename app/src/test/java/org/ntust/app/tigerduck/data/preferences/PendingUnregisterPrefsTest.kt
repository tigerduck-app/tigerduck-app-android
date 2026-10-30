package org.ntust.app.tigerduck.data.preferences

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.auth.DepartingRefresh
import org.ntust.app.tigerduck.auth.DepartingSession
import org.ntust.app.tigerduck.push.DepartingDeviceUnregister
import org.ntust.app.tigerduck.push.OwedUnregister
import org.ntust.app.tigerduck.push.PendingUnregister
import java.io.File
import org.ntust.app.tigerduck.util.JvmContext

/** The owed unregister outlasts the sign-out and the process; see DepartingDeviceUnregister. */
class PendingUnregisterPrefsTest {

    private val prefs = JvmContext()
        .getSharedPreferences("pending-unregister-test", Context.MODE_PRIVATE)

    @Test
    fun `what is owed reads back after a restart, and is gone once cleared`() {
        PendingUnregisterPrefs(prefs).save(PendingUnregister("device-1", DepartingSession("access-1", "refresh-1", 42L, "https://backend.example/v3"), 7L))

        // A new instance over the same file, as the next launch has.
        val read = PendingUnregisterPrefs(prefs).load()
        assertEquals("device-1", read?.deviceId)
        assertEquals("access-1", read?.session?.accessToken)
        assertEquals("refresh-1", read?.session?.refreshToken)
        assertEquals(42L, read?.session?.accessExpiresAt)
        assertEquals("https://backend.example/v3", read?.session?.baseUrl)
        assertEquals(7L, read?.owedAt)

        PendingUnregisterPrefs(prefs).clear()
        assertNull(PendingUnregisterPrefs(prefs).load())
    }

    @Test
    fun `a save the disk refuses says so, for the send it counts not to go out`() {
        // SharedPreferences puts the edit in memory first, and only then reports the failed write
        // with commit(): what is read back in this process is the edit, not what the disk has.
        class RefusingEditor(private val inner: SharedPreferences.Editor) : SharedPreferences.Editor by inner {
            override fun putString(key: String, value: String?) = apply { inner.putString(key, value) }
            override fun putLong(key: String, value: Long) = apply { inner.putLong(key, value) }
            override fun putInt(key: String, value: Int) = apply { inner.putInt(key, value) }
            override fun remove(key: String) = apply { inner.remove(key) }
            override fun commit(): Boolean {
                inner.commit()
                return false
            }
        }
        val refusing = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor = RefusingEditor(prefs.edit())
        }
        val pending = PendingUnregister("device-1", DepartingSession("access-1", "refresh-1", 42L, "https://backend.example/v3"), 7L)

        assertTrue(PendingUnregisterPrefs(prefs).save(pending))
        assertFalse(PendingUnregisterPrefs(refusing).save(pending.with(unansweredRefreshes = 1)))
        assertEquals("the refused edit is what this process reads back", 1, PendingUnregisterPrefs(prefs).load()?.unansweredRefreshes)
    }

    @Test
    fun `a sign-out's clearing of the session leaves what is owed`() {
        PendingUnregisterPrefs(prefs).save(PendingUnregister("device-1", DepartingSession("access-1", "refresh-1", 42L, "https://backend.example/v3"), 7L))

        // What CredentialManager.clearNtustCredentials() removes of the session.
        prefs.edit()
            .remove(V3TokenStore.KEY_ACCESS_TOKEN)
            .remove(V3TokenStore.KEY_REFRESH_TOKEN)
            .remove(V3TokenStore.KEY_EXPIRES_AT)
            .commit()

        assertEquals("refresh-1", PendingUnregisterPrefs(prefs).load()?.session?.refreshToken)
    }

    // The full reset signs out first, so an unregister that failed offline is owed while the
    // reset wipes the credentials.

    private fun owe() = PendingUnregisterPrefs(prefs)
        .save(
            PendingUnregister(
                "device-1",
                DepartingSession("access-1", "refresh-1", Long.MAX_VALUE, "https://backend.example/v3", "B11300001"),
                0L,
            ),
        )

    @Test
    fun `the full reset's wipe keeps what is owed and takes every credential else`() {
        prefs.edit()
            .putString("ntust_student_id", "B11300001")
            .putString("library_token", "library")
            .putString("mail_password", "mail")
            .commit()
        V3TokenStore(prefs).save("access-2", "refresh-2", 99L)
        owe()

        // What CredentialManager.clearAll() runs.
        clearAllButOwedUnregister(prefs)

        assertEquals("refresh-1", PendingUnregisterPrefs(prefs).load()?.session?.refreshToken)
        assertEquals(PendingUnregisterPrefs.KEYS, prefs.all.keys)
    }

    @Test
    fun `after the reset the debt goes out, unless a backend sign-in came first`() = runTest {
        owe()
        clearAllButOwedUnregister(prefs)
        val tokens = V3TokenStore(prefs)
        val deletes = mutableListOf<String>()
        fun unregister() = DepartingDeviceUnregister(
            owed = OwedUnregister(PendingUnregisterPrefs(prefs)),
            deleteDevice = { _, device, token -> deletes += "$device $token"; 204 },
            refresh = { _, _, _ -> DepartingRefresh.Refused },
            sessionAt = { _ -> tokens.refreshToken != null },
            now = { 0L },
            endpoint = { "https://backend.example/v3" },
        )

        // A backend sign-in after the reset moved the device to its account.
        tokens.save("access-2", "refresh-2", Long.MAX_VALUE)
        assertEquals(DepartingDeviceUnregister.Outcome.DONE, unregister().send())
        assertEquals(emptyList<String>(), deletes)
        assertNull(PendingUnregisterPrefs(prefs).load())

        // Without one, the departing account's token goes out.
        tokens.clear()
        owe()
        clearAllButOwedUnregister(prefs)
        assertEquals(DepartingDeviceUnregister.Outcome.DONE, unregister().send())
        assertEquals(listOf("device-1 access-1"), deletes)
        assertNull(PendingUnregisterPrefs(prefs).load())
    }

    @Test
    fun `the full reset's credential wipe is the one that keeps the debt`() {
        // CredentialManager needs the Android keystore, so this reads its source.
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory && !File(dir, "app/src/main/java").isDirectory) {
            dir = dir.parentFile
        }
        val app = if (File(dir, "app/src/main/java").isDirectory) File(dir, "app") else dir!!
        val source = File(app, "src/main/java/org/ntust/app/tigerduck/data/preferences/CredentialManager.kt").readText()
        val clearAll = source.substringAfter("fun clearAll()").substringBefore("\n    }")

        assertTrue(clearAll, "clearAllButOwedUnregister(prefs)" in clearAll)
        assertFalse(clearAll, ".clear()" in clearAll)
    }
}
