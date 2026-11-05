package org.ntust.app.tigerduck.browser.sites

import com.google.gson.Gson
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The school sites the browser knows; unlisted school hosts retain the generic logout rules. */
object BrowserSchoolSites {
    val all: List<SchoolSite> = listOf(
        QueryCourseSite,
        WebCounselingSite,
        DormSite,
        StudentInformationSite,
        CardSystemSite,
        StudentPaySite,
        SuggestionBoxSite,
        Cour01Site,
        CourseSelectionSite,
        PortalSite,
        SchoolMailSite,
        SoftwareSite,
        StudentServicesSite,
        InteruniversitySite,
        SsoSite,
        NetIqSite,
        UniversitySite,
        LibrarySite,
        ResearchEthicsSite,
        ComputerCenterSite,
        DocumentSystemSite,
        MoodleSite,
    )

    fun forUrl(url: String): SchoolSite? {
        val host = url.toHttpUrlOrNull()?.host?.trimEnd('.')?.lowercase() ?: return null
        return all.firstOrNull { host in it.hosts }
    }

    fun isLogout(url: String): Boolean = forUrl(url)?.isLogout(url) == true

    fun signInLink(url: String): String? {
        val page = url.toHttpUrlOrNull() ?: return null
        if (!page.isHttps || page.port != 443 || page.username.isNotEmpty() || page.password.isNotEmpty() || page.query != null) return null
        return forUrl(url)?.signInLink(page)
    }

    /** CSS also matches controls a site draws after its first page loads. Moodle keeps its tidy script. */
    val logoutScripts: List<Pair<String, Set<String>>> = all.filter { it !== MoodleSite && it.logoutSelectors.isNotEmpty() }.map { site ->
        val css = Gson().toJson(site.logoutSelectors.joinToString(", ") + " { display: none !important; }")
        """(function() {
  var style = document.createElement('style');
  style.textContent = $css;
  (document.head || document.documentElement).appendChild(style);
})();""" to site.hosts.flatMap { listOf("https://$it", "http://$it") }.toSet()
    }
}
