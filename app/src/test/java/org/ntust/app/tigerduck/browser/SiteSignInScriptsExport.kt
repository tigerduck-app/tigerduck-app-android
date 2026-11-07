package org.ntust.app.tigerduck.browser

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.browser.sites.BrowserSchoolSites
import java.io.File

/**
 * Writes the sign-in scripts as the browser makes them for each listed form to
 * `build/site-sign-in-scripts`, where `tools/page-scripts/site-sign-in.test.cjs` runs them in jsdom:
 * the JVM has no DOM to run them in. The fill carries a placeholder token, which the jsdom run
 * swaps for the one its mark answers. Fake values only.
 */
class SiteSignInScriptsExport {

    @Test
    fun `writes each listed form's scripts for the jsdom run`() {
        val out = File("build/site-sign-in-scripts").apply {
            deleteRecursively()
            mkdirs()
        }
        PAGES.forEach { (name, url) ->
            val page = checkNotNull(BrowserSchoolSites.page(url)) { url }
            val form = checkNotNull(BrowserSchoolSites.signInForm(url)) { url }
            File(out, "$name-mark.js").writeText(BrowserSiteSignInScripts.mark(page, form, STUDENT_ID))
            if (form.password != null) {
                File(out, "$name-fill.js").writeText(BrowserSiteSignInScripts.fill(page, form, STUDENT_ID, PASSWORD, TOKEN))
            }
        }
        val ethics = PAGES.getValue("ethics")
        val page = checkNotNull(BrowserSchoolSites.page(ethics))
        val form = checkNotNull(BrowserSchoolSites.signInForm(ethics))
        File(out, "ethics-fill-evil.js").writeText(BrowserSiteSignInScripts.fill(page, form, STUDENT_ID, EVIL_PASSWORD, TOKEN))
        File(out, "property.txt").writeText(BrowserSiteSignInScripts.TOKEN_PROPERTY)

        // A mark for every form, a fill for every form with a password (the suggestion box has none), and the exotic one.
        assertEquals(PAGES.size + (PAGES.size - 1) + 1, out.list()!!.count { it.endsWith(".js") })
    }

    private companion object {
        const val STUDENT_ID = "B00000001"
        const val PASSWORD = "not-a-password"
        const val EVIL_PASSWORD = "fake-pass'\"\\</script>\u2028\u2029\n"
        const val TOKEN = "0123456789abcdef0123456789abcdef"

        val PAGES = mapOf(
            "ethics" to "https://stuinfosys.ntust.edu.tw/Authenticate/Account/EnrollStudent/ethicscourse",
            "social" to "https://stuinfosys.ntust.edu.tw/Authenticate/SSOAccount/Index/SSOcgesocial",
            "card" to "https://cardsystem.ntust.edu.tw/std/index.php/user/login",
            "pay" to "https://studentpay.ntust.edu.tw/",
            "box" to "https://suggestionbox.ntust.edu.tw/login",
        )
    }
}
