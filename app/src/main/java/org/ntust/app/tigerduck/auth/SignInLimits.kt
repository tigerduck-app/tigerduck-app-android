package org.ntust.app.tigerduck.auth

/**
 * How long an automatic sign-in with the stored password waits after one that failed: the
 * backend's here, and the Moodle token renewal's SSO sign-in (MoodleTokenRenewal).
 */
internal const val SIGN_IN_PAUSE_MS = 5 * 60_000L

/**
 * The deadline for each whole call of an automatic sign-in: the backend's sign-in here, and each
 * step of the Moodle token harvest, its SSO sign-in among them (MoodleTokenService); the sign-in
 * by hand makes the same calls. The clients time each read alone, so a server that trickles its
 * answer could hold the sign-in, which runs under NonCancellable, and whoever waits on it, without
 * end. A step takes a few seconds, redirects included; 30 s is above both clients' read timeouts
 * (15 s and 20 s), so a step with one slow read they let through still ends in time.
 */
internal const val SIGN_IN_CALL_TIMEOUT_SECONDS = 30L
