package org.ntust.app.tigerduck.browser.sites

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A school's site keeps its logout routes, controls and optional SSO entry together. */
interface SchoolSite {
    val hosts: Set<String>
    val logoutRules: List<SiteLogoutRule>
    val logoutSelectors: List<String>
    fun signInLink(page: HttpUrl): String? = null

    fun isLogout(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.host.trimEnd('.').lowercase() in hosts && logoutRules.any { it.matches(parsed) }
    }
}

/** A route's decoded path, with a query action where the site routes its logout through one. */
data class SiteLogoutRule(val path: String, val parameter: String? = null, val value: String? = null) {
    fun matches(url: HttpUrl): Boolean {
        val actual = url.pathSegments.filter(String::isNotEmpty).map { it.substringBefore(';').lowercase() }
        val expected = path.split('/').filter(String::isNotEmpty).map(String::lowercase)
        if (actual.take(expected.size) != expected) return false
        if (parameter == null) return true
        if (actual.size != expected.size) return false
        return url.queryParameterNames.any { name ->
            name.equals(parameter, ignoreCase = true) && url.queryParameterValues(name).any { it.equals(value, ignoreCase = true) }
        }
    }

    fun selectors(hosts: Set<String>): List<String> {
        val target = if (parameter == null) path else "$path?$parameter=$value"
        return (listOf(target) + hosts.flatMap { listOf("https://$it$target", "http://$it$target") }).flatMap {
            listOf("a[href^=\"$it\" i]", "form[action^=\"$it\" i]")
        }
    }
}
