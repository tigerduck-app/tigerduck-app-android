package org.ntust.app.tigerduck.browser

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.ntust.app.tigerduck.browser.sites.SitePage
import org.ntust.app.tigerduck.browser.sites.SiteSignInForm

/**
 * The two scripts [BrowserSiteSignIn] runs in a school site's sign-in page, in the page's own
 * world: the [mark], which fills the student id and marks the page, and the [fill], which gives the
 * password to the page so marked and to no other. Each checks the page's origin and path itself,
 * since the page may have moved on, and answers [FAILED] when it throws.
 *
 * evaluateJavascript takes no arguments: the values go into the scripts as [literal]s, which no
 * quote, backslash or line separator in them can end.
 */
object BrowserSiteSignInScripts {

    /**
     * The window property the mark keeps its token on, named for TigerDuck so no page's own clashes
     * with it: not enumerable, and neither writable nor configurable, so no page script replaces it.
     */
    const val TOKEN_PROPERTY = "__tigerduckSiteSignInToken"

    /** What evaluateJavascript answers for a script that threw. */
    const val FAILED = "\"failed\""

    /** A token the mark makes: 128 random bits, in lower-case hex. */
    private val TOKEN = Regex("[0-9a-f]{32}")

    /** What the mark answered: the page's token, null where it has none, and the address it was made at. */
    data class Marked(val token: String?, val href: String)

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
     * Fills the student id where the form is empty and its fields stand as [FIELDS] requires, and,
     * for a form with a password field, makes the page's token: answers it and `location.href`.
     * Where a property of the token's name is there already, made by an earlier mark or by the page,
     * it answers no token, and so its own run gives no password: no value a page could have set is
     * ever taken for a token. A run that made the token goes on as before.
     */
    fun mark(page: SitePage, form: SiteSignInForm, studentId: String): String = script(page, form, studentId) {
        """    const href = location.href;
    const token = passwordField === null || Object.getOwnPropertyDescriptor(window, key) ? null : (() => {
      const made = Array.from(crypto.getRandomValues(new Uint8Array(16)), (byte) => byte.toString(16).padStart(2, '0')).join('');
      Object.defineProperty(window, key, { value: made, writable: false, configurable: false, enumerable: false });
      return made;
    })();
    const found = fields();
    if (found && !found.id.value) type(found.id, studentId);
    return { token, href };"""
    }

    /**
     * Gives [password] to the page that holds [token], beside the stored student id alone: fills
     * the id first where it is empty, then reads every field again, since the id's events may have
     * changed the page, and writes the password only when the page, its token, the same id field,
     * form and password field, the form's own origin, the id's value and the empty password all
     * still stand.
     */
    fun fill(page: SitePage, form: SiteSignInForm, studentId: String, password: String, token: String): String =
        script(page, form, studentId, "  const password = ${literal(password)}, token = ${literal(token)};\n") {
            """    const marked = () => {
      if (location.origin !== origin || location.pathname !== path) return null;
      const kept = Object.getOwnPropertyDescriptor(window, key);
      if (!kept || kept.value !== token) return null;
      const found = fields();
      return found && found.secret ? found : null;
    };
    const before = marked();
    if (!before || before.secret.value) return false;
    if (!before.id.value) type(before.id, studentId);
    const after = marked();
    if (!after || after.id !== before.id || after.secret !== before.secret || after.form !== before.form) return false;
    if (after.id.value !== studentId || after.secret.value) return false;
    type(after.secret, password);
    return true;"""
        }

    /** The token and address the mark answered with; null for any other answer. A malformed token reads as none. */
    fun marked(answer: String?): Marked? = runCatching {
        val reply = JsonParser.parseString(answer ?: return null)
        if (!reply.isJsonObject) return null
        val fields = reply.asJsonObject
        val href = fields.get("href")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null
        val token = fields.get("token")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        Marked(token?.takeIf(TOKEN::matches), href)
    }.getOrNull()

    private fun script(page: SitePage, form: SiteSignInForm, studentId: String, extra: String = "", body: () -> String) = """(function() {
  const origin = ${literal(page.origin)}, path = ${literal(page.path)}, key = ${literal(TOKEN_PROPERTY)};
  const studentIdField = ${literal(form.studentId)}, studentId = ${literal(studentId)};
  const passwordField = ${literal(form.password)};
$extra  try {
    if (location.origin !== origin || location.pathname !== path) return null;
$FIELDS
${body()}
  } catch (error) {
    return 'failed';
  }
})();"""

    /**
     * The form's fields: the student id, and the password field where the form has one, as inputs
     * in one form that posts to the page's own origin, the password field of type password; null
     * otherwise. A button or input of the form whose formaction posts elsewhere leaves it null too.
     * Where the form posts is read through the prototypes' getters, as a control named `action`
     * stands in for `form.action` and an element named `baseURI` for `document.baseURI`. `type` writes a value as the student would, with its input and change
     * events.
     */
    private const val FIELDS = """    const field = (name) => Array.prototype.find.call(
      document.getElementsByName(name), (element) => element instanceof HTMLInputElement && element.form !== null);
    const getter = (type, name) => Object.getOwnPropertyDescriptor(type.prototype, name).get;
    const postsHere = (address) => new URL(address, getter(Node, 'baseURI').call(document)).origin === origin;
    const postsElsewhere = (form) => ['button', 'input'].some((tag) =>
      Array.prototype.some.call(document.getElementsByTagName(tag), (element) => element.form === form &&
        element.hasAttribute('formaction') && !postsHere(element.getAttribute('formaction'))));
    const fields = () => {
      const id = field(studentIdField), secret = passwordField === null ? null : field(passwordField);
      if (!id || (passwordField !== null && (!secret || secret.type !== 'password' || secret.form !== id.form))) return null;
      if (!postsHere(getter(HTMLFormElement, 'action').call(id.form)) || postsElsewhere(id.form)) return null;
      return { id, secret, form: id.form };
    };
    const type = (element, value) => {
      element.value = value;
      element.dispatchEvent(new Event('input', { bubbles: true }));
      element.dispatchEvent(new Event('change', { bubbles: true }));
    };"""
}
