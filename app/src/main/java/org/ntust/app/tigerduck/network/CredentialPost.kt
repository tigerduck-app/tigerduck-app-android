package org.ntust.app.tigerduck.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.ntust.app.tigerduck.shared.executeCredentialPost

/**
 * Executes [request], the POST of a sign-in form that carries the password, by
 * [executeCredentialPost]'s rules: the form goes only where it was addressed, and once. A 307 or a
 * 308 is followed with the same form only to the school's sign-in host itself, over HTTPS, as a
 * load balancer in front of it may send. The caller closes the response it gets.
 */
internal fun OkHttpClient.executeSignInFormPost(request: Request): Response? =
    executeCredentialPost(request, formMayGoOnTo = ::isSignInHost)
