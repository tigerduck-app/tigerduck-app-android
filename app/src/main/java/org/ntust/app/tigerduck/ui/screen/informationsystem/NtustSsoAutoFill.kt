package org.ntust.app.tigerduck.ui.screen.informationsystem

import org.json.JSONObject
import org.ntust.app.tigerduck.network.HtmlParser
import java.net.URI

/**
 * True when [url] is NTUST's SSO login page, by host alone — a WebView has no cheap way to read
 * the loaded document's markup the way [HtmlParser.isSSOLoginPage] reads raw HTML, but
 * [HtmlParser.isSsoHost]'s hosts serve nothing else, so the same signal that gates the OkHttp-side
 * check is sufficient here too. Deferring to [HtmlParser] rather than keeping a second host list
 * here is the point: NTUST's SSO is split across more than one hostname (`ssoam` and `ssoam2`),
 * and a caller that hand-rolls its own copy of that set is exactly how one of them quietly stops
 * being recognized — which is what happened here before this deferred to the shared check.
 */
internal fun isSsoLoginUrl(url: String?): Boolean {
    val host = runCatching { URI(url ?: return false).host }.getOrNull() ?: return false
    return HtmlParser.isSsoHost(host)
}

/**
 * JS that fills whichever of NTUST's SSO login forms the page turns out to be and submits it, so
 * a WebView that lands here doesn't leave a signed-in user staring at a login page.
 *
 * [HtmlParser.isSsoHost]'s two hostnames are, confirmed by fetching each directly, two genuinely
 * different login products with no shared markup:
 *  - `ssoam2.ntust.edu.tw`: `<form id="loginForm">`, fields named `Username` / `Password` — the
 *    same names [org.ntust.app.tigerduck.network.SsoLoginService] posts over OkHttp.
 *  - `ssoam.ntust.edu.tw/nidp/app/login` (NetIQ Access Manager): `<form id="IDPLogin">`, fields
 *    named `Ecom_User_ID` / `Ecom_Password`, and its submit button is bound to an *invisible*
 *    reCAPTCHA (`data-bind="loginButton2"`) — solved by Google's own JS the moment the button is
 *    genuinely clicked, which is exactly why this clicks the button rather than calling
 *    `form.submit()` (see below).
 *
 * Rather than hardcode both shapes forever, this tries each known field-name pair first, then
 * falls back to a generic rule that also covers a login page we haven't seen yet: find
 * the page's password field (every login form has exactly one `input[type="password"]`), take
 * its own form, and treat the first non-hidden text-like input in that form as the identifier
 * field. Restricting to that fallback only when the known names don't match keeps it from ever
 * running against a form whose shape we've actually verified.
 *
 * Run as a single [android.webkit.WebView.evaluateJavascript] call, never a persistent
 * `addJavascriptInterface` bridge the page's own script could also reach. [JSONObject.quote]
 * renders each credential as a JS string literal safe against a value containing a quote,
 * backslash or newline — the credentials exist in this script for exactly one evaluation and
 * are never logged, stored, or referenced again once it runs.
 *
 * Two things beyond a plain "set .value and call .submit()":
 *  - Dispatches real `input`/`change` events after setting each field's value. Setting `.value`
 *    directly does not fire either, so a page whose own JS only enables its submit button (or
 *    runs client-side validation) on those events would otherwise see the fields as still empty.
 *  - Clicks the form's actual submit control instead of calling `form.submit()` when one exists.
 *    The DOM `submit()` method deliberately does not run the form's `submit` event handler (nor
 *    any click handler bound to the button), so a login page that attaches its own JS there — a
 *    CSRF token, an invisible reCAPTCHA, or anything else computed at submit time — would
 *    silently drop a programmatic `.submit()` on the floor. A dispatched click goes through the
 *    same path a human tap would.
 *
 * Returns a short status string purely for the caller's own diagnostic logging — never anything
 * derived from the page's content, and nothing about the credentials themselves.
 */
internal fun buildSsoAutoFillScript(studentId: String, password: String): String {
    val user = JSONObject.quote(studentId)
    val pass = JSONObject.quote(password)
    return """
        (function() {
            function fire(el) {
                el.dispatchEvent(new Event('input', { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
            }
            function isTextLike(el) {
                var type = (el.getAttribute('type') || 'text').toLowerCase();
                return type === 'text' || type === 'email';
            }

            // Known shapes first — ssoam2's #loginForm/Username/Password, and NetIQ Access
            // Manager's #IDPLogin/Ecom_User_ID/Ecom_Password.
            var u = document.querySelector(
                '[name="Username"], #Username, [name="Ecom_User_ID"], #Ecom_User_ID'
            );
            var p = document.querySelector(
                '[name="Password"], #Password, [name="Ecom_Password"], #Ecom_Password'
            );

            var form = (p || u) ? (p || u).form : null;

            // Unknown shape: fall back to "the page's own password field", then its own form's
            // first text-like input, rather than giving up.
            if (!p) {
                p = document.querySelector('input[type="password"]');
                form = p ? p.form : null;
            }
            if (!p) return 'no-password-field';
            if (!form) return 'no-form';
            if (!u) {
                var inputs = form.querySelectorAll('input');
                for (var i = 0; i < inputs.length; i++) {
                    if (inputs[i] !== p && isTextLike(inputs[i])) { u = inputs[i]; break; }
                }
            }
            if (!u) return 'no-username-field';

            u.value = $user;
            fire(u);
            p.value = $pass;
            fire(p);

            var submitEl = form.querySelector(
                'button[type="submit"], input[type="submit"], button:not([type])'
            );
            if (submitEl) {
                submitEl.click();
                return 'clicked-submit';
            }
            form.submit();
            return 'form-submit';
        })();
    """.trimIndent()
}
