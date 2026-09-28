package org.ntust.app.tigerduck.network

import okhttp3.HttpUrl

object HtmlParser {

    data class FormData(
        val action: String,
        val inputs: List<Pair<String, String>>
    )

    /**
     * NTUST's SSO/CAS front door, observed under two different hostnames — which one a given
     * service's redirect chain lands on varies per service, and both are the same login system.
     * Every caller that needs to recognize "we got bounced to the SSO wall" must check both, from
     * this one place, or silently miss whichever one it forgot (as `courseselection`/`stuinfosys`
     * working while a portal link bounced to the other host, unrecognized, once did).
     */
    private val SSO_HOSTS = setOf("ssoam.ntust.edu.tw", "ssoam2.ntust.edu.tw")

    /** [SSO_HOSTS], for callers outside this file that need the actual set — not just a
     *  membership check — such as [org.ntust.app.tigerduck.ui.screen.informationsystem.
     *  syncCookiesToWebView] pruning stale correlation cookies on both hosts. */
    val ssoHosts: Set<String> get() = SSO_HOSTS

    fun isSsoHost(host: String): Boolean = host in SSO_HOSTS

    fun isSSOLoginPage(html: String, url: HttpUrl): Boolean {
        if (!isSsoHost(url.host)) return false
        // Two different login products live under the two SSO hosts (see
        // NtustSsoAutoFill.kt's doc comment): ssoam2's #loginForm with
        // Username/Password, and ssoam's NetIQ Access Manager #IDPLogin with
        // Ecom_User_ID/Ecom_Password. Missing the second shape here used to
        // make this function report "not an SSO page" for a service that
        // bounced to ssoam, which SsoLoginService then took as a false
        // "already logged in".
        return html.contains("id=\"loginForm\"") ||
                html.contains("id=\"IDPLogin\"") ||
                (html.contains("name=\"Username\"") && html.contains("name=\"Password\"")) ||
                (html.contains("name=\"Ecom_User_ID\"") && html.contains("name=\"Ecom_Password\""))
    }

    fun findFormById(html: String, id: String): FormData? {
        val escapedId = Regex.escape(id)
        // Accept either quote style around the id, like extractAttribute /
        // extractTagAttribute — a single-quoted id would otherwise make the
        // login form invisible and stall the SSO flow.
        val formRegex = Regex(
            "<form[^>]*id=[\"']$escapedId[\"'][^>]*>(.*?)</form>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val match = formRegex.find(html) ?: return null
        val formHtml = match.value
        val action = extractAttribute(formHtml, "form", "action") ?: ""
        val inputs = extractInputFields(formHtml)
        return FormData(action, inputs)
    }

    fun findOIDCBridgeForm(html: String): FormData? {
        val formRegex = Regex(
            "<form[^>]*>(.*?)</form>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        for (match in formRegex.findAll(html)) {
            val formHtml = match.value
            val action = extractAttribute(formHtml, "form", "action") ?: ""
            if (action.lowercase().contains("logout")) continue
            if (action.isEmpty()) continue

            val inputs = extractInputFields(formHtml)
            if (inputs.isEmpty()) continue

            val names = inputs.map { it.first }.toSet()
            if (names.contains("Username") || names.contains("Password")) continue

            val isOIDC =
                (names.contains("code") && names.contains("state") && names.contains("iss")) ||
                        names.contains("id_token") ||
                        names.contains("SAMLResponse") ||
                        names.contains("RelayState") ||
                        names.contains("wresult") ||
                        names.contains("wctx")

            if (isOIDC) return FormData(action, inputs)
        }
        return null
    }

    fun extractInputFields(html: String): List<Pair<String, String>> {
        val inputRegex = Regex("<input[^>]*>", RegexOption.IGNORE_CASE)
        return inputRegex.findAll(html).mapNotNull { match ->
            val tag = match.value
            val name = extractTagAttribute(tag, "name") ?: return@mapNotNull null
            if (name.isEmpty()) return@mapNotNull null
            val value = extractTagAttribute(tag, "value") ?: ""
            name to value
        }.toList()
    }

    private fun extractAttribute(html: String, tag: String, attribute: String): String? {
        // Accept both quote styles, like extractTagAttribute below. A
        // single-quoted form action would otherwise return null here, which
        // findOIDCBridgeForm treats as action="" and skips the form — silently
        // stalling the SSO bridge traversal.
        val dq = Regex("<$tag[^>]*$attribute=\"([^\"]*)\"[^>]*>", RegexOption.IGNORE_CASE)
        dq.find(html)?.let { return decodeHtmlEntities(it.groupValues[1]) }
        val sq = Regex("<$tag[^>]*$attribute='([^']*)'[^>]*>", RegexOption.IGNORE_CASE)
        return sq.find(html)?.groupValues?.getOrNull(1)?.let { decodeHtmlEntities(it) }
    }

    private fun extractTagAttribute(tag: String, attribute: String): String? {
        // Try double quotes
        val dq = Regex("$attribute=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
        dq.find(tag)?.let { return decodeHtmlEntities(it.groupValues[1]) }
        // Try single quotes
        val sq = Regex("$attribute='([^']*)'", RegexOption.IGNORE_CASE)
        sq.find(tag)?.let { return decodeHtmlEntities(it.groupValues[1]) }
        return null
    }

    private fun decodeHtmlEntities(s: String): String =
        s.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace(Regex("&#(\\d+);")) { m ->
                m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            }
            .replace(Regex("&#x([0-9a-fA-F]+);")) { m ->
                m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            }
}
