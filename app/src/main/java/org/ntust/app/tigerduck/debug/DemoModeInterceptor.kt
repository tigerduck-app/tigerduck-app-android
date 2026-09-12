package org.ntust.app.tigerduck.debug

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fails every outbound request while [DebugFixtureStore.demoMode] is on, so the
 * demo account (see [org.ntust.app.tigerduck.demo.DemoAccount]) runs on its
 * bundled data and nothing else.
 *
 * It throws rather than answering with a synthetic response on purpose. An
 * `IOException` out of the call is exactly what the app sees with the radio
 * off, so every screen takes the no-network path it has always had, rather
 * than a new one where a 503 or an empty body reaches a parser that has
 * never been handed one.
 *
 * Installed on all three OkHttp clients: the shared one from [NetworkModule],
 * the NTUST/Moodle one [org.ntust.app.tigerduck.network.NtustSessionManager]
 * builds for its cookie jar, and the one inside
 * [org.ntust.app.tigerduck.shared.LibraryService]. Missing any of them would
 * leave a live route out of the device, and the timetable would be overwritten
 * through whichever one it was.
 *
 * Active in release builds too: that is where the store reviewer signs in.
 */
@Singleton
class DemoModeInterceptor @Inject constructor(
    private val fixtures: DebugFixtureStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!fixtures.demoMode) return chain.proceed(chain.request())
        throw IOException("demo mode: refused ${chain.request().url.host}")
    }
}
