package org.ntust.app.tigerduck.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class HtmlParserTest {

    // -------------------------------------------------------------------------
    // isSSOLoginPage
    // -------------------------------------------------------------------------

    @Test
    fun `isSSOLoginPage returns true for ssoam2 host with id loginForm`() {
        val html = """<html><body><form id="loginForm"></form></body></html>"""
        val url = "https://ssoam2.ntust.edu.tw/login".toHttpUrl()
        assertTrue(HtmlParser.isSSOLoginPage(html, url))
    }

    @Test
    fun `isSSOLoginPage returns true for ssoam2 host with Username and Password fields`() {
        val html = """<input name="Username"><input name="Password">"""
        val url = "https://ssoam2.ntust.edu.tw/auth".toHttpUrl()
        assertTrue(HtmlParser.isSSOLoginPage(html, url))
    }

    @Test
    fun `isSSOLoginPage returns false when host is not ssoam2`() {
        val html = """<form id="loginForm"><input name="Username"><input name="Password"></form>"""
        val url = "https://portal.ntust.edu.tw/login".toHttpUrl()
        assertFalse(HtmlParser.isSSOLoginPage(html, url))
    }

    @Test
    fun `isSSOLoginPage returns false when host matches but no login markers present`() {
        val html = """<html><body><p>Welcome</p></body></html>"""
        val url = "https://ssoam2.ntust.edu.tw/dashboard".toHttpUrl()
        assertFalse(HtmlParser.isSSOLoginPage(html, url))
    }

    @Test
    fun `isSSOLoginPage returns false for empty html on ssoam2 host`() {
        val url = "https://ssoam2.ntust.edu.tw/".toHttpUrl()
        assertFalse(HtmlParser.isSSOLoginPage("", url))
    }

    // -------------------------------------------------------------------------
    // findFormById
    // -------------------------------------------------------------------------

    @Test
    fun `findFormById extracts action and inputs from well-formed form`() {
        val html = """
            <form id="loginForm" action="/auth/submit">
              <input name="Username" value="user1">
              <input name="Password" value="secret">
            </form>
        """.trimIndent()
        val form = HtmlParser.findFormById(html, "loginForm")
        assertNotNull(form)
        assertEquals("/auth/submit", form!!.action)
        assertEquals(2, form.inputs.size)
        assertEquals("user1", form.inputs.first { it.first == "Username" }.second)
        assertEquals("secret", form.inputs.first { it.first == "Password" }.second)
    }

    @Test
    fun `findFormById returns null when id not found`() {
        val html = """<form id="otherForm" action="/nowhere"></form>"""
        assertNull(HtmlParser.findFormById(html, "loginForm"))
    }

    @Test
    fun `findFormById returns null for empty html`() {
        assertNull(HtmlParser.findFormById("", "loginForm"))
    }

    @Test
    fun `findFormById with action in single quotes extracts action correctly`() {
        // Regression test: extractAttribute must handle single-quoted action attribute.
        // Before the fix, a single-quoted action returned null and the SSO bridge
        // would silently skip the form.
        val html = """<form id='loginForm' action='/sso/submit'><input name="tok" value="abc"></form>"""
        val form = HtmlParser.findFormById(html, "loginForm")
        assertNotNull(form)
        assertEquals("/sso/submit", form!!.action)
    }

    @Test
    fun `findFormById id in single quotes is still found via double-quote pattern`() {
        // id attribute itself still uses double quotes — the form tag regex requires id="..."
        val html = """<form id="myForm" action='/path/to/action'><input name="x" value="1"></form>"""
        val form = HtmlParser.findFormById(html, "myForm")
        assertNotNull(form)
        assertEquals("/path/to/action", form!!.action)
        assertEquals("1", form.inputs.first { it.first == "x" }.second)
    }

    // -------------------------------------------------------------------------
    // findOIDCBridgeForm — OIDC code+state+iss
    // -------------------------------------------------------------------------

    @Test
    fun `findOIDCBridgeForm returns form for code, state, iss hidden inputs`() {
        val html = """
            <form action="https://service.ntust.edu.tw/oidc/cb">
              <input name="code" value="authcode">
              <input name="state" value="statevalue">
              <input name="iss" value="https://ssoam2.ntust.edu.tw">
            </form>
        """.trimIndent()
        val form = HtmlParser.findOIDCBridgeForm(html)
        assertNotNull(form)
        assertEquals("https://service.ntust.edu.tw/oidc/cb", form!!.action)
    }

    // -------------------------------------------------------------------------
    // findOIDCBridgeForm — SAMLResponse
    // -------------------------------------------------------------------------

    @Test
    fun `findOIDCBridgeForm returns form for SAMLResponse input`() {
        val html = """
            <form action="https://sp.ntust.edu.tw/saml/acs">
              <input name="SAMLResponse" value="base64data">
              <input name="RelayState" value="relayval">
            </form>
        """.trimIndent()
        val form = HtmlParser.findOIDCBridgeForm(html)
        assertNotNull(form)
        assertEquals("https://sp.ntust.edu.tw/saml/acs", form!!.action)
    }

    // -------------------------------------------------------------------------
    // findOIDCBridgeForm — skips Username/Password forms
    // -------------------------------------------------------------------------

    @Test
    fun `findOIDCBridgeForm returns null when only Username and Password inputs present`() {
        val html = """
            <form action="https://ssoam2.ntust.edu.tw/auth">
              <input name="Username" value="">
              <input name="Password" value="">
            </form>
        """.trimIndent()
        assertNull(HtmlParser.findOIDCBridgeForm(html))
    }

    // -------------------------------------------------------------------------
    // findOIDCBridgeForm — skips logout actions
    // -------------------------------------------------------------------------

    @Test
    fun `findOIDCBridgeForm skips form whose action contains logout`() {
        val html = """
            <form action="/logout?next=home">
              <input name="SAMLResponse" value="base64data">
            </form>
        """.trimIndent()
        assertNull(HtmlParser.findOIDCBridgeForm(html))
    }

    // -------------------------------------------------------------------------
    // findOIDCBridgeForm — skips forms with empty action
    // -------------------------------------------------------------------------

    @Test
    fun `findOIDCBridgeForm skips form with empty action`() {
        val html = """
            <form action="">
              <input name="code" value="abc">
              <input name="state" value="xyz">
              <input name="iss" value="https://ssoam2.ntust.edu.tw">
            </form>
        """.trimIndent()
        assertNull(HtmlParser.findOIDCBridgeForm(html))
    }

    // -------------------------------------------------------------------------
    // findOIDCBridgeForm — returns null for empty html
    // -------------------------------------------------------------------------

    @Test
    fun `findOIDCBridgeForm returns null for html with no forms`() {
        assertNull(HtmlParser.findOIDCBridgeForm("<html><body><p>no forms here</p></body></html>"))
    }

    // -------------------------------------------------------------------------
    // findOIDCBridgeForm — id_token and wresult variants
    // -------------------------------------------------------------------------

    @Test
    fun `findOIDCBridgeForm returns form for id_token input`() {
        val html = """
            <form action="https://service.ntust.edu.tw/oidc/token">
              <input name="id_token" value="jwt.payload.sig">
            </form>
        """.trimIndent()
        val form = HtmlParser.findOIDCBridgeForm(html)
        assertNotNull(form)
    }

    @Test
    fun `findOIDCBridgeForm returns form for wresult input`() {
        val html = """
            <form action="https://service.ntust.edu.tw/wsfed/cb">
              <input name="wresult" value="wsfedtoken">
              <input name="wctx" value="ctx">
            </form>
        """.trimIndent()
        val form = HtmlParser.findOIDCBridgeForm(html)
        assertNotNull(form)
    }

    // -------------------------------------------------------------------------
    // extractInputFields — double-quoted attributes
    // -------------------------------------------------------------------------

    @Test
    fun `extractInputFields extracts name and value from double-quoted attributes`() {
        val html = """<input name="user" value="alice"><input name="role" value="admin">"""
        val fields = HtmlParser.extractInputFields(html)
        assertEquals(2, fields.size)
        assertEquals("alice", fields.first { it.first == "user" }.second)
        assertEquals("admin", fields.first { it.first == "role" }.second)
    }

    // -------------------------------------------------------------------------
    // extractInputFields — single-quoted attributes
    // -------------------------------------------------------------------------

    @Test
    fun `extractInputFields extracts name and value from single-quoted attributes`() {
        val html = """<input name='token' value='xyz123'>"""
        val fields = HtmlParser.extractInputFields(html)
        assertEquals(1, fields.size)
        assertEquals("token", fields[0].first)
        assertEquals("xyz123", fields[0].second)
    }

    // -------------------------------------------------------------------------
    // extractInputFields — input without name is skipped
    // -------------------------------------------------------------------------

    @Test
    fun `extractInputFields skips input tags without a name attribute`() {
        val html = """<input value="ignored"><input name="kept" value="yes">"""
        val fields = HtmlParser.extractInputFields(html)
        assertEquals(1, fields.size)
        assertEquals("kept", fields[0].first)
    }

    // -------------------------------------------------------------------------
    // extractInputFields — input with empty name is skipped
    // -------------------------------------------------------------------------

    @Test
    fun `extractInputFields skips input tags with empty name`() {
        val html = """<input name="" value="nope"><input name="valid" value="ok">"""
        val fields = HtmlParser.extractInputFields(html)
        assertEquals(1, fields.size)
        assertEquals("valid", fields[0].first)
    }

    // -------------------------------------------------------------------------
    // extractInputFields — missing value defaults to empty string
    // -------------------------------------------------------------------------

    @Test
    fun `extractInputFields defaults value to empty string when value attribute absent`() {
        val html = """<input name="novalue">"""
        val fields = HtmlParser.extractInputFields(html)
        assertEquals(1, fields.size)
        assertEquals("novalue", fields[0].first)
        assertEquals("", fields[0].second)
    }

    // -------------------------------------------------------------------------
    // decodeHtmlEntities (tested through extractInputFields results)
    // -------------------------------------------------------------------------

    @Test
    fun `decodeHtmlEntities via input value decodes amp entity`() {
        val html = """<input name="q" value="a&amp;b">"""
        val fields = HtmlParser.extractInputFields(html)
        assertEquals("a&b", fields[0].second)
    }

    @Test
    fun `decodeHtmlEntities via input value decodes numeric 39 entity to apostrophe`() {
        val html = """<input name="q" value="it&#39;s">"""
        val fields = HtmlParser.extractInputFields(html)
        assertEquals("it's", fields[0].second)
    }

    @Test
    fun `decodeHtmlEntities via input value decodes decimal numeric entity 65 to A`() {
        val html = """<input name="q" value="&#65;BC">"""
        val fields = HtmlParser.extractInputFields(html)
        assertEquals("ABC", fields[0].second)
    }

    @Test
    fun `decodeHtmlEntities via input value decodes hex numeric entity x41 to A`() {
        val html = """<input name="q" value="&#x41;BC">"""
        val fields = HtmlParser.extractInputFields(html)
        assertEquals("ABC", fields[0].second)
    }

    @Test
    fun `decodeHtmlEntities via input value decodes lt and gt entities`() {
        val html = """<input name="q" value="&lt;tag&gt;">"""
        val fields = HtmlParser.extractInputFields(html)
        assertEquals("<tag>", fields[0].second)
    }

    @Test
    fun `decodeHtmlEntities via input value decodes quot entity to double quote`() {
        val html = """<input name="q" value="say &quot;hello&quot;">"""
        val fields = HtmlParser.extractInputFields(html)
        assertEquals("""say "hello"""", fields[0].second)
    }

    @Test
    fun `decodeHtmlEntities via form action decodes amp entity in double-quoted action`() {
        val html = """<form id="myForm" action="/redirect?a=1&amp;b=2"><input name="x" value="y"></form>"""
        val form = HtmlParser.findFormById(html, "myForm")
        assertNotNull(form)
        assertEquals("/redirect?a=1&b=2", form!!.action)
    }

    @Test
    fun `decodeHtmlEntities via form action decodes amp entity in single-quoted action`() {
        // Regression: single-quoted action must also have entities decoded.
        val html = """<form id="myForm" action='/redirect?a=1&amp;b=2'><input name="x" value="y"></form>"""
        val form = HtmlParser.findFormById(html, "myForm")
        assertNotNull(form)
        assertEquals("/redirect?a=1&b=2", form!!.action)
    }
}
