package org.ntust.app.tigerduck.push

/**
 * Pushes for the signed-in account: a sync trigger, and the notice to sign in again. Signed out,
 * they are the departing account's, which the server can still send until the unregister a
 * sign-out owes lands (DepartingDeviceUnregister), and would sync or post for whoever is on the
 * phone now. Bulletins and operator pushes reach a signed-out phone on purpose, through its
 * anonymous registration.
 */
private val ACCOUNT_PUSH_KINDS = setOf("sync_trigger", "reauth_required")

/** Whether a push of [kind] is handled, with or without someone [signedIn]. */
internal fun handlesPush(kind: String?, signedIn: Boolean): Boolean =
    signedIn || kind !in ACCOUNT_PUSH_KINDS
