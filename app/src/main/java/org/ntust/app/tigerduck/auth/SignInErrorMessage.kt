package org.ntust.app.tigerduck.auth

import android.content.Context
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.network.SsoLoginError
import org.ntust.app.tigerduck.util.replaceIosArg

/** The message the school sign-in shows for [error]. */
internal fun Context.signInErrorMessage(error: Exception): String = when (error) {
    is SsoLoginError.NetworkError -> getString(R.string.error_network_unavailable)
    is SsoLoginError.LoginFailed -> schoolRefusalMessage(error.reason)
    else -> error.message ?: getString(R.string.error_sign_in_failed)
}

/**
 * What to say when the school refused a sign-in: its own message where its page had one ([reason]),
 * otherwise a request to check the account and password. Mirrors iOS's `SSOLoginError.loginFailed`.
 */
internal fun Context.schoolRefusalMessage(reason: String?): String =
    if (reason.isNullOrBlank()) {
        getString(R.string.error_sso_sign_in_failed)
    } else {
        getString(R.string.error_sso_sign_in_failed_with_reason).replaceIosArg(1, reason)
    }
