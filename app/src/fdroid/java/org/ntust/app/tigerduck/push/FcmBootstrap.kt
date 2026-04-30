package org.ntust.app.tigerduck.push

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import org.ntust.app.tigerduck.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * F-Droid stub. The fdroid flavor ships without Google Play Services, so
 * there is no FCM token to fetch. Bulletins still work — the list view
 * polls on open / pull-to-refresh — there's just no real-time push.
 *
 * Same FQN as the play-flavor implementation so TigerDuckApp.kt in main/
 * can inject and call `start()` regardless of which flavor is being built.
 * The constructor mirrors the play impl so Hilt's binding stays uniform.
 */
@Singleton
class FcmBootstrap @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val registration: PushRegistrationService,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    fun start() = Unit
}
