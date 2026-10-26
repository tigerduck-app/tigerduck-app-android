package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.mail.resolveAttachmentMimeType
import org.ntust.app.tigerduck.network.moodle.MoodleAutologin
import org.ntust.app.tigerduck.files.FileViewer
import org.ntust.app.tigerduck.network.moodle.SHARED_NOT_VIEWED_EXTENSIONS
import org.ntust.app.tigerduck.network.moodle.MoodleWebservice
import org.ntust.app.tigerduck.ui.AppProcessing
import org.ntust.app.tigerduck.ui.openLink
import org.ntust.app.tigerduck.ui.screen.browser.InAppBrowser
import org.ntust.app.tigerduck.util.fileProviderAuthority
import org.ntust.app.tigerduck.util.logSummary
import org.ntust.app.tigerduck.debug.OutsideApp
import org.ntust.app.tigerduck.debug.PretendMissingApps
import java.io.File
import java.net.URLEncoder

/**
 * A page on the Moodle site, opened as every "Open in Moodle" opens one: in the Moodle app where
 * it is installed and the Open Moodle in setting asks for it, else in the browser, signed in.
 * Paths are site-relative.
 */
object MoodlePage {
    fun course(courseId: Int) = "/course/view.php?id=$courseId"
    fun forum(cmid: Int) = "/mod/forum/view.php?id=$cmid"
    fun forums(courseId: Int) = "/mod/forum/index.php?id=$courseId"

    /** A module's page, such as `/mod/resource/view.php?id=5` for the module `resource`. */
    fun module(module: String, cmid: Int) = "/mod/$module/view.php?id=$cmid"
    fun discussion(discussionId: Int) = "/mod/forum/discuss.php?d=$discussionId"
    fun gradeReport(courseId: Int) = "/grade/report/user/index.php?id=$courseId"
    fun assignment(cmid: Int) = "/mod/assign/view.php?id=$cmid"
    fun assignments(courseId: Int) = "/mod/assign/index.php?id=$courseId"
    fun participants(courseId: Int) = "/user/index.php?id=$courseId"
    fun profile(userId: Int, courseId: Int) = "/user/view.php?id=$userId&course=$courseId"

    /** The signed-in account's own profile editor. */
    const val PROFILE_EDITOR = "/user/edit.php"

    /**
     * The Moodle app's link to [path]. The page goes in `redirect` with `=`, `&` and `+` escaped,
     * as iOS escapes it, so a page with two parameters does not split the link's own query.
     */
    fun deepLink(path: String): String {
        val escaped = buildString {
            for (c in path) {
                when {
                    c == '=' || c == '&' || c == '+' || c == '#' || c == ' ' || c.code > 0x7E ->
                        append(URLEncoder.encode(c.toString(), "UTF-8"))
                    else -> append(c)
                }
            }
        }
        return "moodlemobile://https://${MoodleWebservice.SITE_HOST}?redirect=$escaped"
    }

    fun webUrl(path: String) = MoodleWebservice.SITE + path
}

/**
 * Opens [path] in the Moodle app through its `moodlemobile://` link while [inMoodleApp], the Open
 * Moodle in setting, asks for the app, or else, and when no app takes the link, on the website in
 * the TigerDuck browser, signed in; see [MoodleWebPages.of]. A link the Moodle app takes asks for
 * no key.
 */
fun openMoodlePage(
    context: Context,
    path: String,
    inMoodleApp: () -> Boolean = { opensInMoodleApp(context) },
    pages: () -> MoodleWebPages = { MoodleWebPages.of(context) },
) {
    if (inMoodleApp()) {
        val app = Intent(Intent.ACTION_VIEW, MoodlePage.deepLink(path).toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        }
        if (runCatching { context.startActivity(app) }.isSuccess) return
    }
    openMoodleWebPage(context, MoodlePage.webUrl(path), pages = pages)
}

/** Whether the Open Moodle in setting asks for the Moodle app, as it does until set otherwise. */
private fun opensInMoodleApp(context: Context): Boolean =
    AppPreferences.storedMoodleOpenIn(context.applicationContext) != AppPreferences.MOODLE_OPEN_IN_BROWSER

/**
 * Opens [url], a page on the Moodle site, in the TigerDuck browser, or where it does not open in
 * the browser [browserPreference] picks (the Browser setting when null); see [MoodleWebPages.of].
 */
fun openMoodleWebPage(
    context: Context,
    url: String,
    browserPreference: String? = null,
    pages: () -> MoodleWebPages = { MoodleWebPages.of(context) },
) {
    pages().open(context, url, browserPreference)
}

/** Opens a page on the Moodle site; [SignedInMoodleWebPages] in the app, stand-ins in tests. */
fun interface MoodleWebPages {
    /** [browserPreference] is the Browser setting's value, or null for the setting as it is. */
    fun open(context: Context, url: String, browserPreference: String?)

    companion object {
        /** The TigerDuck browser first ([InAppFirstMoodleWebPages]), else [fallback]. */
        fun inAppFirst(fallback: MoodleWebPages): MoodleWebPages =
            InAppFirstMoodleWebPages({ c, url -> InAppBrowser.open(c, url) }, fallback)

        /**
         * The app's: a Moodle page in the TigerDuck browser, which keeps Moodle's session until
         * TigerDuck signs out, whatever "Open links with" says; [SignedInMoodleWebPages] where the
         * browser does not open. [inApp] false skips the browser, for a caller that tried it.
         */
        fun of(context: Context, inApp: Boolean = true): MoodleWebPages {
            val outside = outside(context)
            return if (inApp) inAppFirst(outside) else outside
        }

        private fun outside(context: Context): MoodleWebPages {
            val links = EntryPointAccessors.fromApplication(context.applicationContext, MoodleLinksEntryPoint::class.java)
            val autologin = links.autologin()
            return SignedInMoodleWebPages(
                signIn = { url, waiting -> autologin.signedIn(url, waiting) },
                browserPreference = { links.appPreferences().browserPreference },
                scope = links.appScope(),
                processing = links.appProcessing(),
            )
        }
    }
}

/**
 * A page on the Moodle site in the TigerDuck browser ([openInApp]), whatever the Browser setting
 * says: its session there lasts until TigerDuck signs out, where a Custom Tab or another browser
 * keeps one the app can neither see nor clear. Where the browser declines (a WebView too old to
 * clear, a clearing owed), and for any other site, [fallback] opens it as the setting asks.
 */
internal class InAppFirstMoodleWebPages(
    private val openInApp: (Context, String) -> Boolean,
    private val fallback: MoodleWebPages,
) : MoodleWebPages {
    override fun open(context: Context, url: String, browserPreference: String?) {
        if (isMoodleSite(url) && openInApp(context, url)) return
        fallback.open(context, url, browserPreference)
    }

    private fun isMoodleSite(url: String): Boolean =
        url.toHttpUrlOrNull()?.host?.equals(MoodleWebservice.SITE_HOST, ignoreCase = true) == true
}

/**
 * Opens a page on the Moodle site signed in through [signIn] where a key comes in time, else as
 * a plain link. A signed-in link carries a one-time key, so it goes only to the default browser,
 * named in the intent, or a Custom Tab of it, never to whatever app claims the link; with no
 * browser to name, no key is asked for at all. The key is asked for in [scope], the app's, so
 * leaving the screen meanwhile does not stop the page opening. While [signIn] says it waits on a
 * sign-in, [processing] shows the app-wide spinner.
 */
internal class SignedInMoodleWebPages(
    private val signIn: suspend (url: String, waiting: (Boolean) -> Unit) -> String?,
    private val browserPreference: () -> String,
    private val scope: CoroutineScope,
    private val processing: AppProcessing,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
) : MoodleWebPages {
    override fun open(context: Context, url: String, browserPreference: String?) {
        val preference = browserPreference ?: this.browserPreference()
        val browser = if (MoodleAutologin.isSignInTarget(url)) defaultBrowserPackage(context.packageManager) else null
        if (browser == null) {
            openLink(context, url, preference)
            return
        }
        scope.launch(main) {
            // The app-wide spinner, while the open waits on a sign-in the reader would otherwise
            // wait on with nothing on screen; let go on every way out, before the page opens.
            val hold = processing.newHold()
            val signedIn = try {
                signIn(url) { waiting -> if (waiting) hold.start() else hold.stop() }
            } finally {
                hold.stop()
            }
            if (signedIn == null) {
                openLink(context, url, preference)
            } else {
                runCatching { context.startActivity(signedInPageIntent(context, signedIn, browser, inApp = preference == "inApp")) }
                    .onFailure { openLink(context, url, preference) }
            }
        }
    }
}

/** [url], signed in, for [browser] alone: a Custom Tab of it in the app, else the browser itself. */
internal fun signedInPageIntent(context: Context, url: String, browser: String, inApp: Boolean): Intent {
    val intent = if (inApp) CustomTabsIntent.Builder().build().intent else Intent(Intent.ACTION_VIEW)
    intent.data = url.toUri()
    intent.setPackage(browser)
    intent.addCategory(Intent.CATEGORY_BROWSABLE)
    if (!inApp || context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return intent
}

/**
 * The browser that opens a web page by default, or the only browser there is; null with several
 * and none chosen, or none at all.
 */
internal fun defaultBrowserPackage(packageManager: PackageManager): String? {
    if (PretendMissingApps.isMissing(OutsideApp.BROWSER)) return null
    val probe = Intent(Intent.ACTION_VIEW, "https://example.com/".toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
    val browsers = packageManager.queryIntentActivities(probe, 0).map { it.activityInfo.packageName }.toSet()
    val chosen = packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    // With no default the system's chooser answers, which is no browser.
    return chosen?.takeIf { it in browsers } ?: browsers.singleOrNull()
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface MoodleLinksEntryPoint {
    fun autologin(): MoodleAutologin
    fun appPreferences(): AppPreferences
    fun appProcessing(): AppProcessing

    @ApplicationScope
    fun appScope(): CoroutineScope
}

/**
 * Opens a link from Moodle content, such as an announcement's message: a page on the Moodle site
 * as [openMoodleWebPage] opens it, signed in where it may be, with a `webservice/pluginfile.php`
 * link rewritten to the web path and its token dropped, so no key leaves the app; any other web
 * page as the Browser setting asks; mail as a new message.
 */
fun openMoodleContentLink(
    context: Context,
    url: String,
    browserPreference: String,
    pages: () -> MoodleWebPages = { MoodleWebPages.of(context) },
) {
    val uri = url.toUri()
    when {
        // openLink makes it a new message, and says so when no mail app can.
        uri.scheme.equals("mailto", ignoreCase = true) -> openLink(context, url, browserPreference)
        uri.host.equals(MoodleWebservice.SITE_HOST, ignoreCase = true) ->
            openMoodleWebPage(context, moodleWebLink(url), browserPreference, pages)
        else -> openLink(context, url, browserPreference)
    }
}

/**
 * Opens a downloaded Moodle [file] in another app: a content URI to the app's own copy, readable
 * once by the app picked, so no link with the account's key ever leaves the app. A web page, an
 * SVG or any other XML, which a browser renders and may run scripts in, goes to the share sheet
 * as iOS sends it, not to a viewer, by the type listed, the one the copy's name gives or the
 * name's extension; with [share] every file does. Through
 * the system's chooser, which says so itself when no app takes the file; the read grant rides the
 * wrapped intent's clip, which the chooser passes on.
 */
fun moodleFileIntent(
    context: Context,
    file: File,
    mimeType: String,
    share: Boolean = false,
    extensionLookup: (String) -> String? = { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) },
): Intent {
    val uri = FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
    // The app picked asks the FileProvider for the type, which reads it from the copy's name.
    val extension = file.extension.lowercase()
    val nameType = extension.takeIf { it.isNotEmpty() }?.let(extensionLookup)
    val sharedType = listOfNotNull(mimeType, nameType).firstOrNull(::isSharedNotViewed)
        ?: mimeType.takeIf { share || extension in SHARED_NOT_VIEWED_EXTENSIONS }
    val intent = if (sharedType != null) {
        Intent(Intent.ACTION_SEND).setTypeAndNormalize(sharedType).putExtra(Intent.EXTRA_STREAM, uri)
    } else {
        Intent(Intent.ACTION_VIEW).setDataAndTypeAndNormalize(uri, mimeType)
    }
    intent.clipData = ClipData.newRawUri(file.name, uri)
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return Intent.createChooser(intent, null)
}

/**
 * Opens [file] in the app's own viewer when it can show it ([FileViewer]), otherwise as
 * [moodleFileIntent] says; with [share] in the share sheet, whatever it is. Returns whether it
 * went. A file the app cannot hand out, or a system with no chooser to take it, leaves a warning
 * in the log naming only the failure's class.
 */
fun launchMoodleFile(context: Context, file: File, mimeType: String, share: Boolean = false): Boolean = try {
    val elsewhere = {
        context.startActivity(moodleFileIntent(context, file, mimeType, share = share))
        true
    }
    if (share) {
        elsewhere()
    } else {
        val uri = FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
        FileViewer.open(context, uri, mimeType, file.name, elsewhere)
    }
} catch (e: RuntimeException) {
    Log.w(TAG, "attachment not opened: ${e.logSummary()}")
    false
}

/** A link from a course's materials in the share sheet, as text. It never carries a key. */
fun shareMoodleLink(context: Context, title: String, url: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, url)
        .putExtra(Intent.EXTRA_TITLE, title)
    runCatching { context.startActivity(Intent.createChooser(send, null)) }
        .onFailure { Log.w(TAG, "link not shared: ${it.logSummary()}") }
}

private const val TAG = "MoodleLinks"

private fun isSharedNotViewed(mimeType: String): Boolean {
    val base = mimeType.substringBefore(';').trim().lowercase()
    return base in SHARED_NOT_VIEWED || base.endsWith("+xml")
}

private val SHARED_NOT_VIEWED = setOf("text/html", "text/xml", "application/xml")

/**
 * The type to open a Moodle file as: the one Moodle lists, unless that is its `document/unknown`
 * or none, when the file name's extension decides.
 */
fun moodleFileType(
    listed: String?,
    fileName: String,
    extensionLookup: (String) -> String? = { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) },
): String {
    val declared = listed?.takeUnless { it.substringBefore(';').trim().equals("document/unknown", ignoreCase = true) }
    return resolveAttachmentMimeType(declared.orEmpty(), fileName, extensionLookup)
}

/** [url] as the website serves it: the web-service file path becomes the web one, without a token. */
internal fun moodleWebLink(url: String): String {
    val uri = url.toUri()
    val path = uri.encodedPath.orEmpty()
    if (!path.startsWith("/webservice/pluginfile.php/")) return url
    val builder = uri.buildUpon().encodedPath(path.removePrefix("/webservice")).clearQuery()
    for (name in uri.queryParameterNames) {
        if (name == "token") continue
        uri.getQueryParameters(name).forEach { builder.appendQueryParameter(name, it) }
    }
    return builder.build().toString()
}
