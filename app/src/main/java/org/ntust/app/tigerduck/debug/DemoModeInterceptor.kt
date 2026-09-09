package org.ntust.app.tigerduck.debug

import okhttp3.Interceptor
import okhttp3.Response
import org.ntust.app.tigerduck.BuildConfig
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fails every outbound request while [DebugFixtureStore.demoMode] is on, so a
 * screenshot session runs on fixture data and nothing else.
 *
 * It throws rather than answering with a synthetic response on purpose. An
 * `IOException` out of the call is exactly what the app sees with the radio
 * off, which is the state store screenshots were already being taken in — so
 * every screen's no-network path is the one that has been exercised all along,
 * rather than a new one where a 503 or an empty body reaches a parser that has
 * never been handed one.
 *
 * Installed on all three OkHttp clients: the shared one from [NetworkModule],
 * the NTUST/Moodle one [org.ntust.app.tigerduck.network.NtustSessionManager]
 * builds for its cookie jar, and the one inside
 * [org.ntust.app.tigerduck.shared.LibraryService]. Missing any of them would
 * leave a live route out of the device, and the timetable would be overwritten
 * through whichever one it was.
 *
 * `BuildConfig.DEBUG` is checked first, so in a release build the whole body
 * folds to `chain.proceed` and R8 drops the reference to [DebugFixtureStore].
 */
@Singleton
class DemoModeInterceptor @Inject constructor(
    private val fixtures: DebugFixtureStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!BuildConfig.DEBUG || !fixtures.demoMode) return chain.proceed(chain.request())
        throw IOException("demo mode: refused ${chain.request().url.host}")
    }
}
