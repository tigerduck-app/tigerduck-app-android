package org.ntust.app.tigerduck.library

import androidx.annotation.StringRes
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.shared.LibraryServiceError
import java.io.IOException

/**
 * What the phone says about a failed library request: the library's own words
 * for a refusal or a code it would not issue, and the app's localized ones for
 * no connection, a server that did not answer and a missing account, rather
 * than the raw exception text. [fallback] when nothing else says anything.
 */
internal fun libraryErrorMessage(
    error: Throwable,
    @StringRes fallback: Int,
    getString: (Int) -> String,
): String = when (error) {
    is IOException -> getString(R.string.error_network_unavailable)
    is LibraryServiceError.LoginUnavailable -> getString(R.string.error_invalid_server_response)
    is LibraryServiceError.CredentialsNotFound -> getString(R.string.error_library_credentials_not_found)
    else -> error.message?.takeUnless { it.isBlank() } ?: getString(fallback)
}
