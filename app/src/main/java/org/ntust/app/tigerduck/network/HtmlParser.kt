package org.ntust.app.tigerduck.network

import okhttp3.HttpUrl

object HtmlParser {

    data class FormData(
        val action: String,
        val inputs: List<Pair<String, String>>
    )

    fun isSSOLoginPage(html: String, url: HttpUrl): Boolean {
        if (!url.host.contains("ssoam2.ntust.edu.tw")) return false
        return html.contains("id=\"loginForm\"") ||
               (html.contains("name=\"Username\"") && html.contains("name=\"Password\""))
    }

    fun findFormById(html: String, id: String): FormData? {
        val escapedId = Regex.escape(id)
        val formRegex = Regex(
            "<form[^>]*id=\"$escapedId\"[^>]*>(.*?)</form>",
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

            val isOIDC = (names.contains("code") && names.contains("state") && names.contains("iss")) ||
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
        val regex = Regex(
            "<$tag[^>]*$attribute=\"([^\"]*)\"[^>]*>",
            RegexOption.IGNORE_CASE
        )
        return regex.find(html)?.groupValues?.getOrNull(1)?.let { decodeHtmlEntities(it) }
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
