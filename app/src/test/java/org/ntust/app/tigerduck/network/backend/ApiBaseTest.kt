package org.ntust.app.tigerduck.network.backend

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.network.canonicalBackend
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint

/**
 * One backend across its API versions: which API a base speaks, the base moved to the API the app
 * speaks, and when two bases or a base and a request are one backend.
 */
class ApiBaseTest {

    @Test
    fun `the app speaks v3 until the move to v4`() {
        assertEquals(BackendApi.V3, ApiBase.TARGET)
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
    fun `the default target changes no base`() {
        val bases = listOf(
            "https://api.example.test/v3",
            "https://api.example.test/prefix/v3/",
            "https://api.example.test/v4",
            "https://api.example.test",
            "http://10.0.2.2:40000/v3",
        )

        assertEquals(bases.associateWith { it }, bases.associateWith { ApiBase.of(it) })
    }

    @Test
    fun `a base speaks v4 when its last segment says so, and v3 otherwise`() {
        val apis = mapOf(
            "https://api.example.test/v4" to BackendApi.V4,
            "https://api.example.test/v4/" to BackendApi.V4,
            "https://api.example.test/prefix/v4" to BackendApi.V4,
            "https://api.example.test/v3" to BackendApi.V3,
            "https://api.example.test" to BackendApi.V3,
            "https://api.example.test/v4x" to BackendApi.V3,
            "https://api.example.test/v4/sync" to BackendApi.V3,
            "https://v4" to BackendApi.V3,
            "not a url/v4" to BackendApi.V3,
        )

        assertEquals(apis, apis.keys.associateWith { BackendApi.of(it) })
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
    fun `the endpoint setting speaks the API the app does`() {
        val default = canonicalBackend(BuildConfig.PUSH_BASE_URL)
        val settings = mapOf(
            null to ApiBase.of(default),
            "https://api.example.test/v3" to ApiBase.of("https://api.example.test/v3"),
            "https://API.Example.TEST:443/prefix/v3/" to ApiBase.of("https://api.example.test/prefix/v3"),
            "https://api.example.test/v4" to "https://api.example.test/v4",
            "https://api.example.test" to "https://api.example.test",
        )

        assertEquals(settings, settings.keys.associateWith { resolveAnnouncementEndpoint(it).url })
    }
}
