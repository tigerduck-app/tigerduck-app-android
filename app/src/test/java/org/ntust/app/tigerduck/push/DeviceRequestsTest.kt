package org.ntust.app.tigerduck.push

import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.DeviceSecretPrefs
import org.ntust.app.tigerduck.network.backend.BackendError
import org.ntust.app.tigerduck.network.backend.BackendException
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The refusal a request of the secret records, when another request bound the secret while it was
 * out: the refusal answered the older one, and the secret owns the registration now.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DeviceRequestsTest {

    private val context: Application = RuntimeEnvironment.getApplication()
    private val store = DeviceSecretPrefs(context.getSharedPreferences("credentials-requests-test", Context.MODE_PRIVATE))
    private val identity = PushIdentity(AppPreferences(context), context, store) { false }

    private fun refusal() = BackendException(BackendError(403, "device_secret_invalid"), "anonymous register failed: HTTP 403")

    /** Requests whose first one is answered only after [during] went out and was answered. */
    private fun requests(during: suspend (DeviceRequests) -> Unit): DeviceRequests {
        var first = true
        lateinit var requests: DeviceRequests
        requests = DeviceRequests(identity) {
            if (first) {
                first = false
                during(requests)
                throw refusal()
            }
        }
        return requests
    }

    @Test
    fun `a refusal of a request out while another bound the secret is not recorded`() = runBlocking {
        val requests = requests { it.send("fcm-1", serverPushEnabled = true) }

        try {
            requests.send("fcm-2", serverPushEnabled = true)
            fail("the refusal is thrown")
        } catch (e: BackendException) {
            assertEquals(403, e.error.status)
        }

        assertFalse(store.refused)
    }

    @Test
    fun `a refused opt-out out while another request bound the secret is not kept`() = runBlocking {
        val requests = requests { it.send("fcm-1", serverPushEnabled = true) }

        val thrown = runCatching { requests.sendOptOut("fcm-2", serverPushEnabled = false) }.exceptionOrNull()

        assertTrue("got $thrown", thrown is BackendException)
        assertFalse(store.refused)
    }

    @Test
    fun `a refused opt-out on a device that starts over at the next launch is kept`() = runBlocking {
        val requests = requests {}

        requests.sendOptOut("fcm-2", serverPushEnabled = false)

        assertTrue(store.refused)
    }
}
