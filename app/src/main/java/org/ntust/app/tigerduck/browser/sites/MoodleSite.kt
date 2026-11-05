package org.ntust.app.tigerduck.browser.sites

import org.ntust.app.tigerduck.network.moodle.MoodleWebservice
import org.ntust.app.tigerduck.browser.BrowserRouting

object MoodleSite : SchoolSite {
    override val hosts = setOf(MoodleWebservice.SITE_HOST.lowercase())
    override val logoutRules = listOf(SiteLogoutRule("/login/logout.php"), SiteLogoutRule("/auth/oidc/logout.php"))
    override val logoutSelectors: List<String> get() = listOf(LOGOUT_SELECTOR, LEFT_OVER_SELECTOR)

    /** Moodle's logout, by where a link or form goes: its own pages and auth_oidc's, in full as Moodle writes them, and as paths. */
    private val LOGOUT_TARGETS = logoutRules.map { it.path }.flatMap { listOf(MoodleWebservice.SITE + it, it) }
    private val LOGOUT_LINKS = LOGOUT_TARGETS.map { "a[href^=\"$it\"]" }
    private val LOGOUT_FORMS = LOGOUT_TARGETS.map { "form[action^=\"$it\"]" }

    /** Every link and form to Moodle's logout. */
    val LOGOUT_SELECTOR: String = (LOGOUT_LINKS + LOGOUT_FORMS).joinToString(", ")

    /**
     * What Moodle's logout leaves standing on its own: the user menu's divider right above it, the
     * footer's "You are logged in as … (Log out)" line, and the sign-in page's button wrapper. By
     * `:has()` (WebView 105 and later), in a rule of its own, as a browser without it drops the
     * whole rule it is in.
     */
    val LEFT_OVER_SELECTOR: String = (
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

}
