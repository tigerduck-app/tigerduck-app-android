package org.ntust.app.tigerduck

import android.content.Intent
import android.net.Uri
import org.ntust.app.tigerduck.data.sharing.ClassTableShareCode
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A class table share code opened as a link rather than scanned in the app. A share code is a
 * URL on the share host, `HTTPS://SHARE.TIGERDUCK.APP/CLASSTABLE/<code>`, which App Links hand to
 * the app when the camera, Google Lens or another QR app reads one, or when one is tapped as a
 * link (the manifest's autoVerify filter); the share page's "Open in TigerDuck" button opens
 * `tigerduck://share/<type>/<code>`, the same path on the custom scheme.
 *
 * Any web page or app can open either, not only a QR code, so a link does no more than a scan:
 * the import reads it as the camera's code, and nothing is saved before its confirm step.
 *
 * Android matches the manifest filter's scheme and host case-sensitively, and share codes are
 * written in upper case, so a scanner that starts the URL exactly as written may open the browser
 * instead; the web page's button then opens the app. The manifest has no upper-case filter, which
 * verification would not cover. A link that does arrive reads the same in any letter case.
 */
internal object ShareCodeLink {
    /** `tigerduck://share/<code>`, beside the [AppLink] hosts in the manifest. */
    const val HOST = "share"

    /** The share host's root, as App Links deliver its URLs; the decoder ignores letter case. */
    private const val WEB_ROOT = "${AppUrls.SHARE}/"

    private val SHARE_HOST: Uri = Uri.parse(AppUrls.SHARE)

    /**
     * The text the import reads for [link], an intent's data: a share host URL as it came, in
     * whatever letter case, as the decoder trims and upper-cases it itself, but without its query
     * and fragment, which a chat app may tag a link it passes on with (`?fbclid=…`), as the share
     * page and the iPhone app read only the path; and
     * `tigerduck://share/<rest>` as that URL, `https://share.tigerduck.app/<rest>`. Null for any
     * other link, which the rest of the app reads.
     *
     * Anything on the share host counts, the host alone included, as the manifest claims all of
     * it: a newer app's type reaches the decoder, which asks for an update, and a URL that holds
     * no share code is called damaged rather than opening nothing.
     */
    fun codeText(link: Uri?): String? {
        link ?: return null
        return when {
            link.scheme.equals(SHARE_HOST.scheme, ignoreCase = true) &&
                link.host.equals(SHARE_HOST.host, ignoreCase = true) ->
                link.buildUpon().clearQuery().fragment(null).build().toString()
            // Matched exactly, as the manifest's filter and AppLink.parse match a tigerduck:// link.
            link.scheme == AppLink.SCHEME && link.host == HOST ->
                WEB_ROOT + link.encodedPath.orEmpty().removePrefix("/")
            else -> null
        }
    }
}

/**
 * The share code text [intent]'s link carries, for the import to read, or null. The link is taken
 * off [intent] either way, so it opens once per delivery; and none opens for an Activity rebuilt
 * from saved state ([restored]) or relaunched from Recents, which hand back the intent it was
 * first started with. A link of any other kind is left on [intent].
 */
internal fun takeSharedClassTableCode(intent: Intent, restored: Boolean): String? {
    val text = ShareCodeLink.codeText(intent.data) ?: return null
    intent.data = null
    val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
    return text.takeIf { !restored && !fromHistory }
}

/**
 * A share code link that came before it could open: no one signed in, or onboarding not finished.
 * It is held until both are, and its import then asks as a scanned code does, so the student need
 * not scan the code again (decided for both apps, 2026-10-19).
 *
 * In memory only, never in saved state or on disk, so quitting the app loses it. A newer link
 * replaces it, and a sign-out drops it ([drop], from `AuthService.logout`).
 */
@Singleton
class HeldShareLink @Inject constructor() {
    private val held = AtomicReference<String?>(null)

    /** [text], to open now when [mayOpen]; otherwise held, in place of any held before. */
    fun arrived(text: String, mayOpen: Boolean): String? {
        held.set(text.takeUnless { mayOpen })
        return text.takeIf { mayOpen }
    }

    /** The link held, taken, once [mayOpen]. */
    fun release(mayOpen: Boolean): String? = if (mayOpen) held.getAndSet(null) else null

    fun drop() = held.set(null)
}
