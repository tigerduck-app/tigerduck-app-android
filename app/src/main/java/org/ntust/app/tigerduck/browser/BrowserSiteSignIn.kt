package org.ntust.app.tigerduck.browser

import android.util.Log
import android.webkit.WebView
import com.google.gson.Gson
import org.ntust.app.tigerduck.browser.sites.BrowserSchoolSites
import java.security.cert.X509Certificate

/**
 * Fills a school site's own sign-in form with the stored school account, on each main-frame page
 * the browser finishes loading at an address [BrowserSchoolSites.signInForm] lists, as the iPhone
 * does: its empty student id and password fields alone. Its CAPTCHA and its submit stay the
 * student's; nothing is submitted.
 *
 * Nothing is filled for the demo account or with none signed in ([signedIn]), while the browser
 * cannot be used ([usable]), or for a page loaded under a browser session a sign-out or an account
 * change has since ended ([generation]). The WebView is not pinned, so the password goes only to a
 * page whose certificate meets the pins the app's own requests meet ([meetsPins]); any other page
 * gets the student id at most.
 *
 * evaluateJavascript takes no arguments: the values go into the script as JSON string literals,
 * which no quote, backslash or line separator in them can end. The script runs in the page's own
 * world and checks the page's origin and path again as it fills, since the page may have moved
 * on. Neither the script nor its values are logged.
 */
class BrowserSiteSignIn(
    /** Whether a school account other than the demo is signed in. */
    private val signedIn: () -> Boolean,
    private val studentId: () -> String?,
    private val password: () -> String?,
    private val usable: () -> Boolean,
    private val generation: () -> Int,
    /** Whether the certificates a page came with meet the pins in force for its host. */
    private val meetsPins: (host: String, served: List<X509Certificate>) -> Boolean,
) {
    /** Fills the form of [url], the page [web] finished loading in the browser session [session]. */
    fun pageFinished(web: WebView, url: String, session: Int?) {
        val script = script(url, web.certificate?.x509Certificate, session) ?: return
        web.evaluateJavascript(script) { result ->
            if (result == FAILED) Log.w(TAG, "a school site's sign-in form was not filled")
        }
    }

    /** The script that fills [url]'s form, which came with [certificate], in the browser session [session]; null for none. */
    fun script(url: String, certificate: X509Certificate?, session: Int?): String? {
        val form = BrowserSchoolSites.signInForm(url) ?: return null
        val page = BrowserSchoolSites.page(url) ?: return null
        if (session == null || !usable() || generation() != session || !signedIn()) return null
        val id = studentId()?.takeIf { it.isNotBlank() } ?: return null
        val pinned = form.password != null && certificate != null && meetsPins(page.host, listOf(certificate))
        val secret = if (pinned) password()?.takeIf { it.isNotEmpty() } else null
        return """(function() {
  const origin = ${literal(page.origin)}, path = ${literal(page.path)};
  const studentIdField = ${literal(form.studentId)}, studentId = ${literal(id)};
  const passwordField = ${literal(form.password)}, password = ${literal(secret)};
  try {
$FILL
  } catch (error) {
    return 'failed';
  }
})();"""
    }

    companion object {
        private const val TAG = "BrowserSiteSignIn"

        /** What evaluateJavascript answers for a script that threw. */
        const val FAILED = "\"failed\""

        private val json = Gson()

        /**
         * [value] as a JSON string literal in ASCII alone, or `null`: Gson escapes quotes,
         * backslashes, control characters, U+2028 and U+2029, and `<`, `>`, `&`, `=` and `'` too;
         * every other character past ASCII becomes a `\u` escape here.
         */
        fun literal(value: String?): String = if (value == null) "null" else buildString {
            json.toJson(value).forEach { if (it.code in 0x20..0x7e) append(it) else append("\\u%04x".format(it.code)) }
        }

        /**
         * Fills only empty fields, as the student may have typed, and leaves every other field
         * alone: nothing unless the fields share one form that posts to the page's own origin, and
         * the password only beside the stored student id, never beside one the student typed.
         */
        private const val FILL = """    if (location.origin !== origin || location.pathname !== path) return false;
    const field = (name) => Array.prototype.find.call(
      document.getElementsByName(name), (element) => element instanceof HTMLInputElement && element.form !== null);
    const id = field(studentIdField), secret = passwordField === null ? null : field(passwordField);
    if (!id || (passwordField !== null && (!secret || secret.type !== 'password' || secret.form !== id.form))) return false;
    if (new URL(id.form.action).origin !== origin) return false;
    const fills = secret && password !== null && (!id.value || id.value === studentId)
      ? [[id, studentId], [secret, password]] : [[id, studentId]];
    for (const [element, value] of fills) {
      if (element.value) continue;
      element.value = value;
      element.dispatchEvent(new Event('input', { bubbles: true }));
      element.dispatchEvent(new Event('change', { bubbles: true }));
    }
    return true;"""
    }
}
