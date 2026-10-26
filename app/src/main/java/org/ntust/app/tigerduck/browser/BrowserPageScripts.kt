package org.ntust.app.tigerduck.browser

import org.ntust.app.tigerduck.network.moodle.MoodleWebservice

/** What the browser adds to pages it shows, before the pages' own scripts run. */
object BrowserPageScripts {
    /**
     * Lets the page pan. YUI's gesture events, which Moodle still loads (its file picker, its
     * dialogs), put `touch-action: none` inline on the element they listen on, the body among
     * them, wherever the browser has pointer events, and a page whose body says so cannot be
     * scrolled at all: Moodle's profile editor among them. A stylesheet rule marked important
     * outranks an inline style. Added to the document element, as the head may not be there yet.
     * Moodle's pages only ([TOUCH_PAN_ORIGINS]): elsewhere a page may hold its body still on purpose.
     */
    const val TOUCH_PAN = """(function() {
  var style = document.createElement('style');
  style.textContent = 'html, body { touch-action: auto !important; }';
  (document.head || document.documentElement).appendChild(style);
})();"""

    /** Where [TOUCH_PAN] goes, as WebViewCompat.addDocumentStartJavaScript's origin rules. */
    val TOUCH_PAN_ORIGINS: Set<String> = setOf(MoodleWebservice.SITE)

    /** Moodle's logout, by where a link or form goes: its own pages and auth_oidc's, in full as Moodle writes them, and as paths. */
    private val LOGOUT_TARGETS = listOf("/login/logout.php", "/auth/oidc/logout.php").flatMap { listOf(MoodleWebservice.SITE + it, it) }
    private val LOGOUT_LINKS = LOGOUT_TARGETS.map { "a[href^=\"$it\"]" }
    private val LOGOUT_FORMS = LOGOUT_TARGETS.map { "form[action^=\"$it\"]" }

    /** Every link and form to Moodle's logout. */
    internal val LOGOUT_SELECTOR: String = (LOGOUT_LINKS + LOGOUT_FORMS).joinToString(", ")

    /**
     * What Moodle's logout leaves standing on its own: the user menu's divider right above it, the
     * footer's "You are logged in as … (Log out)" line, and the sign-in page's button wrapper. By
     * `:has()` (WebView 105 and later), in a rule of its own, as a browser without it drops the
     * whole rule it is in.
     */
    internal val LEFT_OVER_SELECTOR: String = (
        LOGOUT_LINKS.map { ".dropdown-divider:has(+ $it)" } +
            LOGOUT_LINKS.map { ".logininfo:has($it)" } +
            LOGOUT_FORMS.map { ".singlebutton:has(> $it)" }
        ).joinToString(", ")

    /**
     * Hides Moodle's logout: every link to it, and every form that posts to it (its sign-in page's
     * "Log out" button), by where the link goes, so a link that only names the logout in its query
     * stays; Moodle writes its own links in full. With it go what it would leave standing on its
     * own ([LEFT_OVER_SELECTOR]): elsewhere a divider and a wrapper stay. Moodle's session ends when
     * TigerDuck signs out, which clears the browser; a logout inside would only leave the student
     * at a sign-in the next open may have no key for. A stylesheet, so the user menu Moodle draws
     * later is matched as well; on a WebView without `:has()`, a script does what that rule does,
     * as the page changes. The address itself is refused besides ([BrowserRouting.isMoodleLogout]).
     */
    val HIDE_LOGOUT: String = """(function() {
  var logout = '$LOGOUT_SELECTOR';
  var style = document.createElement('style');
  style.textContent = logout + ' { display: none !important; }\n$LEFT_OVER_SELECTOR { display: none !important; }';
  (document.head || document.documentElement).appendChild(style);
  if (window.CSS && CSS.supports && CSS.supports('selector(:has(*))')) return;
  var hide = function(element) { element.style.setProperty('display', 'none', 'important'); };
  var tidy = function() {
    document.querySelectorAll(logout).forEach(function(found) {
      var above = found.previousElementSibling;
      if (above && above.classList.contains('dropdown-divider')) hide(above);
      var box = found.tagName === 'FORM' ? found.parentElement : found.closest('.logininfo');
      if (box && box.matches(found.tagName === 'FORM' ? '.singlebutton' : '.logininfo')) hide(box);
    });
  };
  document.addEventListener('DOMContentLoaded', tidy);
  new MutationObserver(tidy).observe(document.documentElement, { childList: true, subtree: true });
})();"""

    /**
     * No focus ring where a touch moved the focus. Moodle's scripts move the focus when a tap opens
     * something, its drawer to its close button among them, and Boost rings whatever has focus; a
     * finger needs no ring to see where it is. Where the keyboard, a D-pad or Switch Access moved
     * the focus, `:focus-visible` holds and the ring stays as Boost draws it. A WebView without
     * `:focus-visible` drops the rule and keeps every ring.
     */
    const val TOUCH_FOCUS = """(function() {
  var style = document.createElement('style');
  style.textContent = 'button:focus:not(:focus-visible), a:focus:not(:focus-visible), input:focus:not(:focus-visible), ' +
    'select:focus:not(:focus-visible), textarea:focus:not(:focus-visible), [tabindex]:focus:not(:focus-visible) ' +
    '{ outline: none !important; box-shadow: none !important; }';
  (document.head || document.documentElement).appendChild(style);
})();"""

    /** Where [TOUCH_FOCUS] goes, as WebViewCompat.addDocumentStartJavaScript's origin rules. */
    val TOUCH_FOCUS_ORIGINS: Set<String> = setOf(MoodleWebservice.SITE)

    /** Where [HIDE_LOGOUT] goes, as WebViewCompat.addDocumentStartJavaScript's origin rules. */
    val HIDE_LOGOUT_ORIGINS: Set<String> = setOf(MoodleWebservice.SITE)

    private fun jsList(items: List<String>): String = items.joinToString(", ") { "'$it'" }

    /**
     * Hides a school site's logout, every site's but Moodle's, which [HIDE_LOGOUT] hides by its own
     * rules: a link or a form whose address [BrowserRouting.isSchoolLogout] would refuse, read by
     * the same words, extensions and parameters, and a button, a link or a control whose words are
     * the logout's (登出, Log out, Sign out) or whose script names one. The control alone goes, never
     * an element around it. Run as the page loads and as it changes. The addresses themselves are
     * refused besides, so a control this misses signs no one out.
     */
    val HIDE_SCHOOL_LOGOUT: String = """(function() {
  var moodle = '${MoodleWebservice.SITE_HOST}';
  if (location.hostname.replace(/\.$/, '').toLowerCase() === moodle) return;
  var WORDS = [${jsList(BrowserRouting.SCHOOL_LOGOUT_WORDS)}];
  var EXTENSIONS = [${jsList(BrowserRouting.SCRIPT_EXTENSIONS)}];
  var PARAMETERS = [${jsList(BrowserRouting.ACTION_PARAMETERS)}];
  var letters = function(text) { return text.toLowerCase().replace(/[^a-z]/g, ''); };
  var routeName = function(segment) {
    var path = segment.split(';')[0];
    var dot = path.lastIndexOf('.');
    if (dot > 0 && EXTENSIONS.indexOf(path.slice(dot + 1).toLowerCase()) < 0) return '';
    return letters(dot > 0 ? path.slice(0, dot) : path);
  };
  var isLogout = function(address) {
    var url;
    try { url = new URL(address, location.href); } catch (e) { return false; }
    var host = url.hostname.replace(/\.$/, '').toLowerCase();
    if (host !== 'ntust.edu.tw' && host.slice(-13) !== '.ntust.edu.tw') return false;
    if (host === moodle) return false;
    var segments = url.pathname.split('/');
    for (var i = 0; i < segments.length; i++) {
      var segment = segments[i];
      try { segment = decodeURIComponent(segment); } catch (e) {}
      var name = routeName(segment);
      if (name === 'endsession') return true;
      if (name && WORDS.some(function(word) { return name.slice(-word.length) === word; })) return true;
    }
    var found = false;
    url.searchParams.forEach(function(value, key) {
      if (PARAMETERS.indexOf(key.toLowerCase()) >= 0 && WORDS.indexOf(letters(value)) >= 0) found = true;
    });
    return found;
  };
  var named = /^(log\s*out|log\s*off|sign\s*out|登出|登出系統|安全登出|系統登出|註銷|注销)$/i;
  var scripted = /(log\s*out|log\s*off|sign\s*out)/i;
  var controls = 'a, button, input[type="button"], input[type="submit"], [role="button"], [role="menuitem"], [onclick], ' +
    '[class*="logout" i], [id*="logout" i], [class*="logoff" i], [class*="signout" i]';
  var hide = function(element) { element.style.setProperty('display', 'none', 'important'); };
  var queued = false;
  var tidy = function() {
    queued = false;
    document.querySelectorAll('a[href]').forEach(function(link) { if (isLogout(link.getAttribute('href'))) hide(link); });
    document.querySelectorAll('form[action]').forEach(function(form) { if (isLogout(form.getAttribute('action'))) hide(form); });
    document.querySelectorAll(controls).forEach(function(control) {
      var words = (control.value || control.textContent || '').trim();
      if (named.test(words) || scripted.test(control.getAttribute('onclick') || '')) hide(control);
    });
  };
  var later = function() {
    if (queued) return;
    queued = true;
    requestAnimationFrame(tidy);
  };
  document.addEventListener('DOMContentLoaded', tidy);
  new MutationObserver(later).observe(document.documentElement, {
    childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ['href', 'action', 'onclick']
  });
})();"""

    /** Where [HIDE_SCHOOL_LOGOUT] goes: every school page, as WebViewCompat.addDocumentStartJavaScript's origin rules. */
    val HIDE_SCHOOL_LOGOUT_ORIGINS: Set<String> = setOf("https://ntust.edu.tw", "https://*.ntust.edu.tw")
}
