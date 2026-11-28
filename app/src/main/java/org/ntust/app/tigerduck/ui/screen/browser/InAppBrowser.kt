package org.ntust.app.tigerduck.ui.screen.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import androidx.webkit.WebViewFeature
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.browser.AllowlistEntry
import org.ntust.app.tigerduck.browser.BrowserAllowlist
import org.ntust.app.tigerduck.browser.BrowserLaunchPolicy
import org.ntust.app.tigerduck.browser.BrowserLinksProfile
import org.ntust.app.tigerduck.browser.BrowserCleanup
import org.ntust.app.tigerduck.browser.BrowserRiskAcceptance
import org.ntust.app.tigerduck.browser.BrowserRiskProfiles
import org.ntust.app.tigerduck.browser.CertificateRefusal
import org.ntust.app.tigerduck.browser.BrowserSession
import org.ntust.app.tigerduck.browser.BrowserSignIns

/**
 * Opens pages in the TigerDuck browser, the app's own browser built on a
 * WebView, whatever "Open links with" says. Moodle's pages open in it where
 * the Moodle app does not take them (MoodleWebPages.of), the profile pencil
 * among them; another feature opens a page in it with one call to [open].
 * Sign-in is per site, through [BrowserSignIns]. A link the app was handed
 * opens through [openLink], in a profile of its own signed in to nothing.
 */
object InAppBrowser {
    private const val EXTRA_ENTRIES = "org.ntust.app.tigerduck.browser.ENTRIES"
    private const val EXTRA_ALLOW_EVERY_SITE = "org.ntust.app.tigerduck.browser.ALLOW_EVERY_SITE"
    private const val EXTRA_UNRESTRICTED = "org.ntust.app.tigerduck.browser.UNRESTRICTED"
    private const val EXTRA_ANY_HTTPS_PAGE = "org.ntust.app.tigerduck.browser.ANY_HTTPS_PAGE"
    const val EXTRA_URL = "org.ntust.app.tigerduck.browser.URL"

    /** Set by [openLink]: the browser loads in the links profile. */
    private const val EXTRA_LINKS = "org.ntust.app.tigerduck.browser.LINKS"

    /** Set by [openAnyPage]: the browser lets every page in, in a debug build only. */
    const val EXTRA_ANY_PAGE = "org.ntust.app.tigerduck.browser.ANY_PAGE"

    /** Set by [openAtRisk]: the certificates the student accepted, [BrowserRiskAcceptance.encoded]. */
    private const val EXTRA_RISK_ACCEPTED = "org.ntust.app.tigerduck.browser.RISK_ACCEPTED"

    /** Set by [openAtRisk]: the profile the browser loads in, made in this process. */
    private const val EXTRA_RISK_PROFILE = "org.ntust.app.tigerduck.browser.RISK_PROFILE"

    /**
     * Whether this phone's WebView can delete everything a site keeps,
     * service workers and Cache Storage included. The browser opens only
     * where it can: on an older WebView one student's pages could outlive a
     * sign-out. Set by tests.
     */
    @Volatile
    internal var canClearEverything: () -> Boolean = {
        runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA) }.getOrDefault(false)
    }

    /**
     * Opens [url], an https page, in the TigerDuck browser; false when it is
     * not one, when this phone's WebView cannot be cleared ([canClearEverything]),
     * while a deletion is unconfirmed or a clearing owed ([BrowserCleanup]),
     * or when nothing could open, for the caller to open it another way.
     * With no [allowlist], every HTTPS page loads; HTTP pages ask before opening outside.
     * A supplied restriction gains the opening host
     * and the SSO hosts used by automatic sign-in.
     */
    fun open(context: Context, url: String, allowlist: BrowserAllowlist? = null): Boolean {
        if (url.toHttpUrlOrNull()?.isHttps != true) return false
        val intent = Intent(context, InAppBrowserActivity::class.java).putExtra(EXTRA_URL, url)
        if (allowlist != null) {
            intent.putStringArrayListExtra(EXTRA_ENTRIES, ArrayList(allowlist.entries.map { it.text }))
            intent.putExtra(EXTRA_ALLOW_EVERY_SITE, allowlist.allowEverySite)
            intent.putExtra(EXTRA_UNRESTRICTED, allowlist.anyPage)
            intent.putExtra(EXTRA_ANY_HTTPS_PAGE, allowlist.anyHttpsPage)
        }
        return start(context, intent)
    }

    /**
     * Settings › Developer › Browser allowlist's "Open a page": opens [url], any http or https
     * page, in a TigerDuck browser that lets every page in, past any allowlist, for a developer
     * to try a page. A release build ([debug] false) opens nothing. As [open] otherwise.
     */
    fun openAnyPage(context: Context, url: String): Boolean = openAnyPage(context, url, BuildConfig.DEBUG)

    @VisibleForTesting
    internal fun openAnyPage(context: Context, url: String, debug: Boolean): Boolean {
        if (!debug || url.toHttpUrlOrNull() == null) return false
        val intent = Intent(context, InAppBrowserActivity::class.java).putExtra(EXTRA_URL, url).putExtra(EXTRA_ANY_PAGE, true)
        return start(context, intent)
    }

    /**
     * Opens [refusal]'s page ([CertificateRefusal.pageToOpen]) at the student's risk, in place of the
     * browser [from] opened, with its restriction: a new browser in a WebView profile of its own made
     * now, signed in to nothing ([BrowserRiskProfiles]), which goes past the certificate error of that
     * host and that leaf only, with those [accepted] already. False where the WebView has no profiles,
     * or as [open] is.
     */
    fun openAtRisk(context: Context, from: Intent, refusal: CertificateRefusal, accepted: BrowserRiskAcceptance?): Boolean {
        val page = refusal.pageToOpen ?: return false
        if (!canClearEverything() || !riskProfiles.supported()) return false
        val profile = riskProfiles.create(BrowserCleanup.of(context)) ?: return false
        val acceptance = (accepted ?: BrowserRiskAcceptance()).accepting(refusal)
        val intent = Intent(from).setClass(context, InAppBrowserActivity::class.java).putExtra(EXTRA_URL, page)
            .putStringArrayListExtra(EXTRA_RISK_ACCEPTED, acceptance.encoded())
            .putExtra(EXTRA_RISK_PROFILE, profile)
            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        // The opener's restriction as it stood, with the first host it gained at its opening.
        if (from.hasExtra(EXTRA_ENTRIES)) intent.putStringArrayListExtra(EXTRA_ENTRIES, ArrayList(allowlistFor(from).entries.map { it.text }))
        return start(context, intent)
    }

    /**
     * Opens [url], an https link the app was handed (a mail's, a bulletin's, the website; "Open links
     * with" set to the TigerDuck browser), in the links profile ([BrowserLinksProfile]): signed in to
     * nothing of the app's, with every HTTPS page let in. False where the WebView has no profiles,
     * or as [open] is, for the caller to open the link in the phone's browser: never in the app's
     * own profile.
     */
    fun openLink(context: Context, url: String): Boolean {
        if (url.toHttpUrlOrNull()?.isHttps != true || !linksProfile.supported()) return false
        return start(context, Intent(context, InAppBrowserActivity::class.java).putExtra(EXTRA_URL, url).putExtra(EXTRA_LINKS, true))
    }

    /** Whether [openLink] opened this browser, or the one it opened at risk from. */
    internal fun opensLinks(intent: Intent): Boolean = intent.getBooleanExtra(EXTRA_LINKS, false)

    /** The profile the links the app was handed load in; a test's own. */
    @Volatile
    internal var linksProfile: BrowserLinksProfile = BrowserLinksProfile.shared

    /** The profiles a browser opened at risk loads in; a test's own. */
    @Volatile
    internal var riskProfiles: BrowserRiskProfiles = BrowserRiskProfiles.shared

    /** What a browser [openAtRisk] opened accepted; null for one opened any other way. */
    internal fun riskAcceptance(intent: Intent): BrowserRiskAcceptance? =
        BrowserRiskAcceptance.decode(intent.getStringArrayListExtra(EXTRA_RISK_ACCEPTED))

    /** The profile a browser [openAtRisk] opened loads in. */
    internal fun riskProfile(intent: Intent): String? = intent.getStringExtra(EXTRA_RISK_PROFILE)

    private fun start(context: Context, intent: Intent): Boolean {
        if (!canClearEverything() || !BrowserSession.usable || BrowserCleanup.of(context).isPending()) return false
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** Each launch carries its caller's restriction, with its opening host and SSO redirects added. */
    internal fun allowlistFor(intent: Intent, debug: Boolean = BuildConfig.DEBUG): BrowserAllowlist {
        val entries = intent.getStringArrayListExtra(EXTRA_ENTRIES)
        val requested = entries?.let {
            BrowserAllowlist(
                it.mapNotNull { text -> AllowlistEntry.parse(text) },
                allowEverySite = intent.getBooleanExtra(EXTRA_ALLOW_EVERY_SITE, false),
                anyPage = intent.getBooleanExtra(EXTRA_UNRESTRICTED, false),
                anyHttpsPage = intent.getBooleanExtra(EXTRA_ANY_HTTPS_PAGE, false),
            )
        }
        val policy = BrowserLaunchPolicy.allowlist(intent.getStringExtra(EXTRA_URL).orEmpty(), requested)
        return if (debug && intent.getBooleanExtra(EXTRA_ANY_PAGE, false)) policy.lettingAnyPageIn() else policy
    }

    /** The page a browser starts on: [url] when it is an https page, or any web page for [anyPage]. */
    internal fun startPage(url: String?, anyPage: Boolean): String? {
        val parsed = url?.toHttpUrlOrNull() ?: return null
        return url.takeIf { parsed.isHttps || anyPage }
    }
}

