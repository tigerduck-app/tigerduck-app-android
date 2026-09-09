package org.ntust.app.tigerduck.auth

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * There are two auth signals in this app and they are not interchangeable:
 *
 * - `AuthService.authState` — "is a user signed in". Backed by the stored
 *   NTUST credentials, so it is **durable**: it survives process death,
 *   reboots and upgrades, and flips only on a real login or logout.
 * - `NtustSessionManager.cookiesValid` — "can a request to NTUST go out right
 *   now". Backed by an **in-memory** cookie jar plus a one-hour TTL, so it
 *   reads false after every process restart until something re-logs in.
 *
 * Anything that only touches the local cache must ask the first question. It
 * is easy to reach for the second by accident, and the failure is silent:
 * the feature simply stops after a reboot or a background kill, looking like
 * a resolver bug rather than an auth one.
 *
 * That shipped: `LiveActivityManager` gated on session liveness even though
 * `refreshInternal()` reads nothing but `DataCache` JSON and the academic
 * calendar, so the Live Update disappeared after every process restart and
 * did not come back until the user manually signed in again.
 *
 * A liveness check is only correct immediately before a network call. This
 * test pins the set of files allowed to make one, so a new misuse fails here
 * instead of in the field. If you are adding a file to [ALLOWED], be sure it
 * is about to hit the network — and consider `AuthService.ensureAuthenticated()`
 * instead, which re-logs in from stored credentials rather than just
 * reporting that it cannot.
 */
class AuthSignalUsageTest {

    private companion object {
        const val LIVENESS_SIGNAL = "cookiesValid"

        /** Files that legitimately ask whether a network call can go out now. */
        val ALLOWED = setOf(
            // Declares it.
            "network/NtustSessionManager.kt",
            // ensureAuthenticated(): the early-out that skips a redundant SSO
            // round-trip when the session is already warm.
            "auth/AuthService.kt",
            // Refuses to scrape the score page without a session rather than
            // parsing NTUST's login redirect as if it were grades.
            "network/NtustScoreService.kt",
        )

        /** Source roots to scan: every variant that ships in the phone app. */
        val ROOTS = listOf("src/main/java", "src/play/java", "src/fdroid/java")
    }

    private fun appDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val here = dir
            if (File(here, "app/src/main/java").isDirectory) return File(here, "app")
            if (File(here, "src/main/java").isDirectory) return here
            dir = here.parentFile
        }
        throw AssertionError("Could not locate the :app module from ${File("").absolutePath}")
    }

    @Test
    fun `session liveness is only consulted where a network call follows`() {
        val app = appDir()
        val pkgPrefix = "org/ntust/app/tigerduck/"

        val offenders = ROOTS
            .map { File(app, it) }
            .filter { it.isDirectory }
            .flatMap { root ->
                root.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .filter { it.readText().contains(LIVENESS_SIGNAL) }
                    .map { file ->
                        file.relativeTo(root).path.removePrefix(pkgPrefix)
                    }
            }
            .toSortedSet()
            .filterNot { it in ALLOWED }

        assertEquals(
            "These files consult `$LIVENESS_SIGNAL`, which is false after every " +
                "process restart until something re-logs in. If the file only reads " +
                "local cache, use `AuthService.authState` instead — otherwise it will " +
                "silently stop working after a reboot. If it really is about to make a " +
                "network call, prefer `AuthService.ensureAuthenticated()` and add the " +
                "file to ALLOWED in this test.\nOffenders:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * The allowlist is only meaningful while every entry is real; a stale one
     * would silently license a path that no longer exists.
     */
    @Test
    fun `every allowlisted file exists and still uses the signal`() {
        val app = appDir()
        val pkgPrefix = "org/ntust/app/tigerduck/"
        val missing = ALLOWED.filterNot { rel ->
            ROOTS.any { root ->
                File(app, "$root/$pkgPrefix$rel").let { it.isFile && it.readText().contains(LIVENESS_SIGNAL) }
            }
        }
        assertEquals(
            "Allowlisted in this test but no longer present / no longer uses " +
                "`$LIVENESS_SIGNAL`. Remove from ALLOWED:\n" + missing.joinToString("\n"),
            emptyList<String>(),
            missing,
        )
    }
}
