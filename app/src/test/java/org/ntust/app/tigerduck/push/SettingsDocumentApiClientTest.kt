package org.ntust.app.tigerduck.push

import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [extractConflictServerJson]'s error-handling contract: a
 * `write()` 409 body that isn't exactly `{"server": {...}}` must surface
 * as [SettingsDocumentApiException], never a raw `org.json.JSONException`
 * — see that function's KDoc for why (self-hosted backends, reverse
 * proxies, etc. can return an unexpected 409 shape in normal use, and a
 * raw `JSONException` sails past a caller's
 * `catch (e: SettingsDocumentApiException)` rebase-and-retry handler).
 *
 * `SettingsDocumentApiClient.write()`'s 409 branch is exactly:
 * ```
 * if (text.isBlank()) throw SettingsDocumentApiException(...)
 * val serverJson = extractConflictServerJson(text, namespace)
 * return@use SettingsWriteResult.Conflict(parseEnvelope(serverJson, type, gson::fromJson))
 * ```
 * so calling [extractConflictServerJson] with the same `text` a 409
 * response body would produce exercises that exact parsing step.
 *
 * This stops short of driving a live `SettingsDocumentApiClient.write()`
 * call end to end (real `OkHttpClient` response, real 409 status code)
 * because that additionally requires working `AppPreferences` and
 * `AuthTokenManager` instances. Both need a real Android `Context`:
 * `AppPreferences` calls `context.getSharedPreferences(...)` directly in
 * its primary constructor, and `AuthTokenManager`'s `CredentialManager`
 * calls `EncryptedSharedPreferences.create(...)` via the Android Keystore.
 * None of `SettingsDocumentApiClient`, `AppPreferences`, `AuthTokenManager`,
 * or `CredentialManager` is `open`, so they can't be subclassed to stub
 * that out either. This module's unit tests run on the plain JVM with no
 * Robolectric, no MockWebServer, and no mocking library (`app/build.gradle.kts`'s
 * test deps are `libs.junit` only — see e.g. `NotificationSettingsDocumentTest`,
 * this package's existing test, which is Gson-only for the same reason).
 * Wiring any of those in is a bigger change than this fix's scope.
 * [extractConflictServerJson] is `internal` rather than `private`
 * specifically so this test can reach the real parsing logic without that
 * machinery — see its KDoc.
 */
class SettingsDocumentApiClientTest {

    private val namespace = "notification"

    @Test
    fun `conflict body that is valid JSON but missing the server key raises SettingsDocumentApiException`() {
        val body = """{"error": "conflict", "namespace": "notification"}"""

        val thrown = assertThrows(SettingsDocumentApiException::class.java) {
            extractConflictServerJson(body, namespace)
        }

        assertTrue(
            "expected message to mention the namespace, was: ${thrown.message}",
            thrown.message.orEmpty().contains(namespace),
        )
    }

    @Test
    fun `conflict body that is not JSON at all raises SettingsDocumentApiException`() {
        val body = "<html>502 Bad Gateway</html>"

        val thrown = assertThrows(SettingsDocumentApiException::class.java) {
            extractConflictServerJson(body, namespace)
        }

        assertTrue(
            "expected message to mention the namespace, was: ${thrown.message}",
            thrown.message.orEmpty().contains(namespace),
        )
    }

    @Test
    fun `conflict body whose server value is not an object raises SettingsDocumentApiException`() {
        // Valid JSON, has the "server" key, but it's the wrong shape —
        // distinct from "key absent entirely".
        val body = """{"error": "conflict", "namespace": "notification", "server": "oops"}"""

        assertThrows(SettingsDocumentApiException::class.java) {
            extractConflictServerJson(body, namespace)
        }
    }

    @Test
    fun `well-formed conflict body still extracts the server object`() {
        val serverJson = """{"document": {"assignments": null}, "revision": 4}"""
        val body = """{"error": "conflict", "namespace": "$namespace", "server": $serverJson}"""

        val extracted = extractConflictServerJson(body, namespace)

        // Compare through JSONObject rather than as raw strings — key
        // order in the round-tripped text isn't guaranteed.
        assertEquals(JSONObject(serverJson).toString(), JSONObject(extracted).toString())
    }

    // [parseEnvelope] is the other half of this file's catch-clause contract — see its
    // KDoc in SettingsDocumentApiClient.kt. It takes the deserialize step as a plain
    // function parameter specifically so these tests can substitute one, rather than
    // routing through a real `Gson` — see that KDoc for why a real `Gson` can't be made
    // to raise a raw CancellationException out of `fromJson` at all (it's `final`, and
    // `fromJson` itself catches `IllegalStateException`, which CancellationException is,
    // and rewraps it as `JsonSyntaxException` before returning).

    @Test
    fun `malformed document body reaches the caller as SettingsDocumentApiException`() {
        val body = "not json at all"

        val thrown = assertThrows(SettingsDocumentApiException::class.java) {
            parseEnvelope(body, NotificationSettingsDocument::class.java, Gson()::fromJson)
        }

        assertTrue(
            "expected message to mention the malformed-response wrapping, was: ${thrown.message}",
            thrown.message.orEmpty().contains("malformed response"),
        )
    }

    @Test
    fun `CancellationException raised while parsing the document is not converted`() {
        // Valid envelope JSON throughout - the point is that *deserializing* the
        // "document" object is where the cancellation lands, simulating a coroutine
        // cancelled while the real gson::fromJson is on the stack inside parseEnvelope.
        // The deserialize step itself throws unconditionally, standing in for that,
        // without needing a real cancelled CoroutineScope in a plain-JVM test (and
        // without routing through a real Gson - see the comment above).
        val body = """{"document": {}, "revision": 1}"""

        assertThrows(CancellationException::class.java) {
            parseEnvelope(body, NotificationSettingsDocument::class.java) { _, _ ->
                throw CancellationException("cancelled while parsing the settings document")
            }
        }
    }
}
