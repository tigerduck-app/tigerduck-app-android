package org.ntust.app.tigerduck.wear.data

import android.content.Context
import org.ntust.app.tigerduck.shared.LibraryService
import org.ntust.app.tigerduck.wear.BuildConfig

/**
 * The watch's one [LibraryService], for the whole process. A sign-in with the
 * stored password outlives the page that asked for it, so its refusal is
 * counted; a page opened again meanwhile joins it rather than sending the
 * password a second time within the same wait, as a service of its own would.
 */
object WatchLibraryServiceHolder {

    @Volatile
    private var service: LibraryService? = null

    fun get(context: Context): LibraryService {
        return service ?: synchronized(this) {
            service ?: LibraryService(
                WatchLibraryCredentialStore.get(context),
                isDebugBuild = BuildConfig.DEBUG,
                tls = WatchTlsPins.get(context).tls,
            ).also { service = it }
        }
    }
}
