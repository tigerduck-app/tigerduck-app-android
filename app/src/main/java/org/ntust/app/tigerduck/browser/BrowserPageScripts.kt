package org.ntust.app.tigerduck.browser

import org.ntust.app.tigerduck.network.moodle.MoodleWebservice
import org.ntust.app.tigerduck.browser.sites.MoodleSite
import org.ntust.app.tigerduck.browser.sites.BrowserSchoolSites

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

    internal val LOGOUT_SELECTOR: String get() = MoodleSite.LOGOUT_SELECTOR
    internal val LEFT_OVER_SELECTOR: String get() = MoodleSite.LEFT_OVER_SELECTOR
    val HIDE_LOGOUT: String get() = MoodleSite.HIDE_LOGOUT

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
    val HIDE_LOGOUT_ORIGINS: Set<String> = MoodleSite.hosts.flatMap { listOf("https://$it", "http://$it") }.toSet()

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
    val HIDE_SCHOOL_LOGOUT_ORIGINS: Set<String> = setOf("https://ntust.edu.tw", "https://*.ntust.edu.tw", "http://ntust.edu.tw", "http://*.ntust.edu.tw")

    /** What every WebView the browser opens in runs before a page's own scripts, each with its origins. */
    val DOCUMENT_START: List<Pair<String, Set<String>>> = listOf(
        TOUCH_PAN to TOUCH_PAN_ORIGINS,
        HIDE_LOGOUT to HIDE_LOGOUT_ORIGINS,
        HIDE_SCHOOL_LOGOUT to HIDE_SCHOOL_LOGOUT_ORIGINS,
        TOUCH_FOCUS to TOUCH_FOCUS_ORIGINS,
    ) + BrowserSchoolSites.logoutScripts
}
