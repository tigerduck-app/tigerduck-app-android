package org.ntust.app.tigerduck.network.backend

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.canonicalBackend
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.sync.SyncClock
import org.ntust.app.tigerduck.sync.SyncClockStore
import org.ntust.app.tigerduck.sync.SyncPolicies
import org.ntust.app.tigerduck.util.JvmContext

/**
 * One backend across its API versions: which API a base speaks, the base moved to the API the app
 * speaks, and when two bases or a base and a request are one backend.
 */
class ApiBaseTest {

    @Test
    fun `the app speaks v4`() {
        assertEquals(BackendApi.V4, ApiBase.TARGET)
    }

    @Test
    fun `a base ending in v3 moves to the target, keeping everything else`() {
        val moves = mapOf(
            "https://api.example.test/v3" to "https://api.example.test/v4",
            "https://api.example.test/prefix/v3" to "https://api.example.test/prefix/v4",
            "https://api.example.test/v3/" to "https://api.example.test/v4/",
            "https://api.example.test:8443/v3" to "https://api.example.test:8443/v4",
            "http://192.168.1.5:40000/v3" to "http://192.168.1.5:40000/v4",
            // Spelled as given: moving is not canonicalizing.
            "https://API.Example.TEST/v3" to "https://API.Example.TEST/v4",
        )

        assertEquals(moves, moves.keys.associateWith { ApiBase.of(it, BackendApi.V4) })
        assertEquals(moves.keys.associateWith { it }, moves.keys.associateWith { ApiBase.of(it, BackendApi.V3) })
    }

    @Test
    fun `a base that does not end in v3 is as given`() {
        val bases = listOf(
            "https://api.example.test",
            "https://api.example.test/",
            "https://api.example.test/prefix",
            "https://api.example.test/v4",
            "https://api.example.test/v3x",
            "https://api.example.test/v3/sync",
            "https://api.example.test/v3?x=1",
            "https://api.example.test/%76%33",
            "https://v3",
            "https://v3/",
            "not a url/v3",
        )

        listOf(BackendApi.V3, BackendApi.V4).forEach { target ->
            assertEquals("to $target", bases.associateWith { it }, bases.associateWith { ApiBase.of(it, target) })
        }
    }

    @Test
    fun `by default a base ending in v3 moves to v4, and no other base moves`() {
        val moves = mapOf(
            "https://api.example.test/v3" to "https://api.example.test/v4",
            "https://api.example.test/prefix/v3/" to "https://api.example.test/prefix/v4/",
            "http://10.0.2.2:40000/v3" to "http://10.0.2.2:40000/v4",
            "https://api.example.test/v4" to "https://api.example.test/v4",
            "https://api.example.test" to "https://api.example.test",
            "https://api.example.test/prefix" to "https://api.example.test/prefix",
        )

        assertEquals(moves, moves.keys.associateWith { ApiBase.of(it) })
    }

    @Test
    fun `a self-hosted backend's base ending in v3 moves to v4 too, as iOS moves any server's`() {
        // S3: the move is by the base's last segment alone, whoever runs the backend.
        val settings = mapOf(
            "https://tigerduck.self-hosted.example/v3" to "https://tigerduck.self-hosted.example/v4",
            "https://self-hosted.example/tigerduck/api/v3" to "https://self-hosted.example/tigerduck/api/v4",
            "http://192.168.1.5:8000/v3" to "http://192.168.1.5:8000/v4",
        )

        assertEquals(settings, settings.keys.associateWith { resolveAnnouncementEndpoint(it).url })
        assertEquals(settings.keys.associateWith { BackendApi.V4 }, settings.keys.associateWith { BackendApi.of(resolveAnnouncementEndpoint(it).url) })
    }

    @Test
    fun `a base with no version segment is sent to as given and speaks v4, and another host's is no request of the backend's`() {
        // No version segment: sent to as given, its requests /v4's and its rules too, as iOS speaks any server.
        val unversioned = listOf("https://self-hosted.example", "https://self-hosted.example/tigerduck/api", "http://192.168.1.5:8000")
        assertEquals(unversioned.associateWith { it }, unversioned.associateWith { resolveAnnouncementEndpoint(it).url })
        assertEquals(unversioned.associateWith { BackendApi.V4 }, unversioned.associateWith { BackendApi.of(resolveAnnouncementEndpoint(it).url) })
        // Another host's requests are not the backend's, at either version: no session, no clock read.
        val school = listOf("https://moodle2.ntust.edu.tw/v3/webservice", "https://ssoam2.ntust.edu.tw/v4/account/login")
        for (base in listOf("https://api.example.test/v3", "https://api.example.test/v4")) {
            assertEquals(school.associateWith { false }, school.associateWith { ApiBase.serves(ApiBase.of(base), it.toHttpUrl()) })
        }
    }

    /** What a base's last segment names is [ApiBase]'s to move and compare by; every base speaks `/v4`. */
    @Test
    fun `every base speaks v4, whatever its last segment names`() {
        val named = mapOf(
            "https://api.example.test/v4" to BackendApi.V4,
            "https://api.example.test/v4/" to BackendApi.V4,
            "https://api.example.test/prefix/v4" to BackendApi.V4,
            "https://api.example.test/v3" to BackendApi.V3,
            "https://api.example.test" to null,
            "https://api.example.test/v4x" to null,
            "https://api.example.test/v4/sync" to null,
            "https://v4" to null,
            "not a url/v4" to null,
        )

        assertEquals(named, named.keys.associateWith { BackendApi.named(it) })
        assertEquals(named.keys.associateWith { BackendApi.V4 }, named.keys.associateWith { BackendApi.of(it) })
    }

    @Test
    fun `a backend's key is its canonical base without the version`() {
        val keys = mapOf(
            "https://api.example.test/v3" to "https://api.example.test",
            "https://api.example.test/v4" to "https://api.example.test",
            "https://API.Example.TEST:443/v3/" to "https://api.example.test",
            "https://api.example.test/prefix/v4" to "https://api.example.test/prefix",
            "https://api.example.test:8443/v3" to "https://api.example.test:8443",
            "https://api.example.test/prefix" to "https://api.example.test/prefix",
            "https://API.Example.TEST:443/prefix/" to "https://api.example.test/prefix",
            "https://api.example.test" to "https://api.example.test",
        )

        assertEquals(keys, keys.keys.associateWith { ApiBase.backendKey(it) })
    }

    @Test
    fun `two bases are one backend when only the version or the spelling differs`() {
        val same = listOf(
            "https://api.example.test/v3" to "https://api.example.test/v4",
            "https://api.example.test/v4" to "https://api.example.test/v3",
            "https://API.Example.TEST:443/v3/" to "https://api.example.test/v4",
            "https://api.example.test/prefix/v3" to "https://api.example.test/prefix/v4",
            "https://api.example.test" to "https://api.example.test/",
            "https://API.Example.TEST:443/prefix" to "https://api.example.test/prefix",
        )
        val other = listOf(
            "https://api.example.test/v3" to "https://other.example.test/v4",
            "https://api.example.test/v3" to "https://api.example.test:8443/v4",
            "https://api.example.test/v3" to "http://api.example.test/v4",
            "https://api.example.test/v3" to "https://api.example.test/prefix/v4",
            "https://api.example.test/prefix/v3" to "https://api.example.test/prefix",
            "https://api.example.test/v3" to "https://api.example.test",
        )

        assertEquals(same.associateWith { true }, same.associateWith { (a, b) -> ApiBase.sameBackend(a, b) })
        assertEquals(other.associateWith { false }, other.associateWith { (a, b) -> ApiBase.sameBackend(a, b) })
    }

    @Test
    fun `a backend's prefix keeps an empty segment before the version`() {
        assertEquals("https://api.example.test/tenant/", ApiBase.backendKey("https://api.example.test/tenant//v3"))
        assertEquals(false, ApiBase.sameBackend("https://api.example.test/tenant//v3", "https://api.example.test/tenant/v3"))
        assertEquals(true, ApiBase.sameBackend("https://api.example.test/tenant//v3", "https://api.example.test/tenant//v4"))
    }

    @Test
    fun `trailing slashes are trimmed as BackendServices trims them before it sends`() {
        assertEquals(BackendApi.V4, BackendApi.of("https://api.example.test/v4//"))
        assertEquals("https://api.example.test/v4//", ApiBase.of("https://api.example.test/v3//", BackendApi.V4))
        assertEquals("https://api.example.test", ApiBase.backendKey("https://api.example.test/v3//"))
        assertEquals(true, ApiBase.sameBackend("https://api.example.test/v3//", "https://api.example.test/v3"))
        assertEquals(true, ApiBase.sameBackend("https://api.example.test/v3///", "https://api.example.test/v4"))
        assertEquals(false, ApiBase.sameBackend("https://api.example.test/v3//", "https://api.example.test"))
        assertEquals(true, ApiBase.serves("https://api.example.test/v3//", "https://api.example.test/v4/sync/full".toHttpUrl()))
    }

    @Test
    fun `a backend serves its own paths at either version and no others`() {
        val answers = mapOf(
            "https://api.example.test/v3/sync/full" to true,
            "https://api.example.test/v4/sync/full" to true,
            "https://api.example.test/v4" to true,
            "https://api.example.test/v5/sync/full" to false,
            "https://api.example.test/v4x/sync/full" to false,
            "https://api.example.test/health" to false,
            "https://api.example.test/links/name" to false,
            "https://other.example.test/v4/sync/full" to false,
            "http://api.example.test/v4/sync/full" to false,
        )

        assertEquals(answers, answers.keys.associateWith { ApiBase.serves("https://api.example.test/v3", it.toHttpUrl()) })
        assertEquals(answers, answers.keys.associateWith { ApiBase.serves("https://api.example.test/v4", it.toHttpUrl()) })
        assertEquals(false, ApiBase.serves("not a url", "https://api.example.test/v3/sync/full".toHttpUrl()))
    }

    @Test
    fun `a base without a version serves only its own paths`() {
        val answers = mapOf(
            "https://api.example.test/prefix/sync/full" to true,
            "https://api.example.test/prefix/v4/sync/full" to true,
            "https://api.example.test/v3/sync/full" to false,
            "https://api.example.test/v4/sync/full" to false,
        )

        assertEquals(answers, answers.keys.associateWith { ApiBase.serves("https://api.example.test/prefix", it.toHttpUrl()) })
    }

    @Test
    fun `the endpoint setting speaks v4, the default and a setting ending in v3 alike`() {
        // The default, PUSH_BASE_URL, ends in /v3 in the debug build and the release alike.
        val default = canonicalBackend(BuildConfig.PUSH_BASE_URL).let { if (it.endsWith("/v3")) it.dropLast(2) + "v4" else it }
        val settings = mapOf(
            null to default,
            "https://api.example.test/v3" to "https://api.example.test/v4",
            "https://API.Example.TEST:443/prefix/v3/" to "https://api.example.test/prefix/v4",
            "https://api.example.test/v4" to "https://api.example.test/v4",
            "https://api.example.test" to "https://api.example.test",
        )

        assertEquals(settings, settings.keys.associateWith { resolveAnnouncementEndpoint(it).url })
    }

    @Test
    fun `a setting ending in v3 takes every v4 rule, last writer wins and the clock's stamps`() {
        val context = JvmContext()
        val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "https://api.example.test/v3" }
        val clock = SyncClock(SyncClockStore(context.getSharedPreferences("tigerduck_sync_clock", Context.MODE_PRIVATE)), prefs)
        val ahead = System.currentTimeMillis() + 3_600_000
        clock.heardInSnapshot(ahead * 1_000)

        assertTrue(SyncPolicies(prefs).current.lastWriterWins)
        // Not the wall clock, as /v3's stamp was: never below the backend's clock as last seen.
        assertEquals(ahead + 1, clock.stampMs())
    }
}
