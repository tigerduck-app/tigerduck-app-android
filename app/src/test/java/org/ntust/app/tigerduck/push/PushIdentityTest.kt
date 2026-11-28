package org.ntust.app.tigerduck.push

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.DepartingSession
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.data.preferences.DeviceSecretPrefs
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * The device's id and its secret, as iOS's `PushIdentityTests` (tigerduck-app#953): the secret
 * minted and saved before use, and a refused device starting over at a launch with no session.
 * Each [launch] is a process: a new [PushIdentity] over the same stores.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PushIdentityTest {

    private val context: Application = RuntimeEnvironment.getApplication()
    private val prefs = AppPreferences(context)
    private val credentialPrefs = context.getSharedPreferences("credentials-identity-test", Context.MODE_PRIVATE)
    private val credentials = CredentialManager(credentialPrefs)
    private val store = DeviceSecretPrefs(credentialPrefs)

    @Before
    fun setUp() {
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "made-up-android-id")
    }

    /** The next process, with the stores the app opens. */
    private fun launch() = PushIdentity(prefs, context, credentials)

    private fun launch(secrets: DeviceSecretStore, storesSession: () -> Boolean = { false }) =
        PushIdentity(prefs, context, secrets, storesSession)

    private val secretShape = Regex("[A-Za-z0-9_-]{43}")

    @Test
    fun `a first launch mints a 43-character base64url secret and saves it before use`() {
        val identity = launch()

        val secret = identity.secret()

        assertTrue("got $secret", secret!!.matches(secretShape))
        assertEquals(secret, store.load())
        UUID.fromString(identity.uuid())
    }

    @Test
    fun `a reinstall derives the same id from ANDROID_ID, and a new secret`() {
        val installed = launch()
        val id = installed.uuid()
        val secret = installed.secret()

        // Every key, the id's too, which the full reset keeps.
        context.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        credentialPrefs.edit().clear().commit()
        val reinstalled = launch()

        assertEquals(id, reinstalled.uuid())
        assertNotEquals(secret, reinstalled.secret())
    }

    @Test
    fun `an updated app that holds a device id keeps it and mints only a secret`() {
        prefs.putString("push.device_uuid", "kept-device")

        val identity = launch()

        assertEquals("kept-device", identity.uuid())
        assertEquals(identity.secret(), store.load())
        assertEquals(43, identity.secret()?.length)
    }

    @Test
    fun `a later launch reads the same id and secret`() {
        val first = launch()
        val id = first.uuid()
        val secret = first.secret()

        val later = launch()

        assertEquals(id, later.uuid())
        assertEquals(secret, later.secret())
    }

    @Test
    fun `each minted secret is new`() {
        assertEquals(8, List(8) { mintDeviceSecret() }.toSet().size)
    }

    @Test
    fun `a secret the disk did not take is sent by no request, and the next use mints one again`() {
        var refuses = true
        val failing = object : DeviceSecretStore by store {
            override fun save(secret: String): Boolean = !refuses && store.save(secret)
        }
        val identity = launch(failing)

        assertNull(identity.secret())
        assertNull(store.load())
        refuses = false
        val secret = identity.secret()

        assertEquals(43, secret?.length)
        assertEquals(secret, store.load())
    }

    @Test
    fun `a store that cannot be read mints nothing`() {
        store.save("kept-secret-kept-secret-kept-secret-kept-s")
        val unreadable = object : DeviceSecretStore by store {
            override fun load(): String? = throw SecurityException("made-up unreadable store")
        }

        assertThrows(SecurityException::class.java) { launch(unreadable).secret() }

        assertEquals("kept-secret-kept-secret-kept-secret-kept-s", store.load())
    }

    @Test
    fun `a refused device that launches with no session starts over, with an id ANDROID_ID does not give`() {
        val refused = launch()
        val derived = refused.uuid()
        val oldSecret = refused.secret()
        refused.recordRefusal()

        val next = launch()
        val id = next.uuid()

        assertNotEquals(derived, id)
        UUID.fromString(id)
        assertNotEquals(oldSecret, next.secret())
        assertEquals(next.secret(), store.load())
        assertFalse(store.refused)
        // Kept where the id is, for every later launch.
        assertEquals(id, launch().uuid())
    }

    @Test
    fun `a refused device keeps its id and secret while a session is stored, and starts over after it`() {
        val refused = launch()
        val id = refused.uuid()
        val secret = refused.secret()
        refused.recordRefusal()
        credentials.v3Tokens.save("access-1", "refresh-1", Long.MAX_VALUE)

        val signedIn = launch()

        assertEquals(id, signedIn.uuid())
        assertEquals(secret, signedIn.secret())
        assertTrue(store.refused)
        credentials.v3Tokens.clear()
        assertNotEquals(id, launch().uuid())
    }

    @Test
    fun `a refused device keeps its id while a sign-out's unregister is owed`() {
        val refused = launch()
        val id = refused.uuid()
        refused.recordRefusal()
        credentials.pendingUnregister.save(
            PendingUnregister(id, DepartingSession("access-1", "refresh-1", Long.MAX_VALUE, "https://api.example.test/v4"), 1L),
        )

        assertEquals(id, launch().uuid())
        assertTrue(store.refused)
        assertFalse(launch().startsOverAtNextLaunch())
    }

    @Test
    fun `a refused device whose session cannot be read keeps its id`() {
        val refused = launch(store)
        val id = refused.uuid()
        refused.recordRefusal()

        val next = launch(store) { throw SecurityException("made-up unreadable store") }

        assertEquals(id, next.uuid())
        assertTrue(store.refused)
        assertFalse(next.startsOverAtNextLaunch())
    }

    @Test
    fun `a refusal recorded in this process leaves its id for the life of the process`() {
        val identity = launch()
        val id = identity.uuid()

        identity.recordRefusal()

        assertEquals(id, identity.uuid())
        assertTrue(identity.startsOverAtNextLaunch())
    }

    @Test
    fun `with no refusal recorded, a launch with no session keeps its id and secret`() {
        val first = launch()
        val id = first.uuid()
        val secret = first.secret()

        val next = launch()

        assertEquals(id, next.uuid())
        assertEquals(secret, next.secret())
    }

    @Test
    fun `a request that binds the secret ends the refusal`() {
        val identity = launch()
        identity.recordRefusal()

        identity.clearRefusal()

        assertFalse(store.refused)
        assertEquals(identity.uuid(), launch().uuid())
    }

    /**
     * A process ended between the new id and the refusal's end must find one or the other on disk
     * at its next start: the old id with no refusal would be refused again, a launch later.
     */
    @Test
    fun `the start-over has the new id on disk before the secret and the refusal go`() {
        launch().recordRefusal()
        val committed = mutableMapOf<String, Any?>()
        val disk = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                CommitRecording(context.getSharedPreferences(name, mode), committed)
        }
        var idOnDisk: Any? = null
        val dropping = object : DeviceSecretStore by store {
            override fun startOver() {
                idOnDisk = committed["push.device_uuid"]
                store.startOver()
            }
        }

        val id = PushIdentity(AppPreferences(disk), disk, dropping) { false }.uuid()

        assertEquals(id, idOnDisk)
        assertFalse(store.refused)
    }

    @Test
    fun `a new id the disk did not take leaves the old id, its secret and the refusal for a later launch`() {
        val refused = launch()
        val id = refused.uuid()
        val secret = refused.secret()
        refused.recordRefusal()
        val full = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                CommitRecording(context.getSharedPreferences(name, mode), mutableMapOf(), diskFull = true)
        }

        val next = PushIdentity(AppPreferences(full), full, store) { false }

        assertEquals(id, next.uuid())
        assertEquals(secret, next.secret())
        assertTrue(store.refused)
        assertEquals(id, prefs.getString("push.device_uuid"))
    }

    /**
     * [base], noting in [committed] each string a commit wrote; an apply's are not on disk yet.
     * With [diskFull], a commit changes the values in memory and says the disk did not take them,
     * as Android's does.
     */
    private class CommitRecording(
        private val base: SharedPreferences,
        private val committed: MutableMap<String, Any?>,
        private val diskFull: Boolean = false,
    ) : SharedPreferences by base {
        override fun edit(): SharedPreferences.Editor {
            val real = base.edit()
            return object : SharedPreferences.Editor by real {
                private val strings = mutableMapOf<String, Any?>()

                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    strings[key!!] = value
                    real.putString(key, value)
                    return this
                }

                override fun commit(): Boolean {
                    real.commit()
                    if (diskFull) return false
                    committed += strings
                    return true
                }
            }
        }
    }
}
