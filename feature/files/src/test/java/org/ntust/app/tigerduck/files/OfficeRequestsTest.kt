package org.ntust.app.tigerduck.files

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.files.OfficeRequests.Route

/** The Office viewer's page gets its own files and the one document it shows; nothing else loads. */
class OfficeRequestsTest {

    private val base = "https://${OfficeRequests.HOST}"

    @Test
    fun `the viewer's own files load`() {
        assertEquals(Route.Asset("viewer/office.html"), OfficeRequests.route("$base/viewer/office.html"))
        assertEquals(Route.Asset("viewer/docx-preview.min.js"), OfficeRequests.route("$base/viewer/docx-preview.min.js"))
        assertEquals(Route.Asset("viewer/office.html"), OfficeRequests.route("$base/viewer/office.html?kind=docx"))
    }

    @Test
    fun `the document loads at its one path`() {
        assertEquals(Route.Document, OfficeRequests.route("$base/document"))
    }

    @Test
    fun `anything else is blocked`() {
        // Another site, plain http, another scheme.
        assertEquals(Route.Blocked, OfficeRequests.route("https://example.com/viewer/office.html"))
        assertEquals(Route.Blocked, OfficeRequests.route("http://${OfficeRequests.HOST}/viewer/office.html"))
        assertEquals(Route.Blocked, OfficeRequests.route("file:///android_asset/viewer/office.html"))
        assertEquals(Route.Blocked, OfficeRequests.route("content://media/external/file/1"))
        // A file of the app's that isn't the viewer's, or a way out of its folder.
        assertEquals(Route.Blocked, OfficeRequests.route("$base/viewer/unknown.js"))
        assertEquals(Route.Blocked, OfficeRequests.route("$base/demo.json"))
        assertEquals(Route.Blocked, OfficeRequests.route("$base/viewer/../demo.json"))
        assertEquals(Route.Blocked, OfficeRequests.route("$base/viewer/%2e%2e/demo.json"))
        assertEquals(Route.Blocked, OfficeRequests.route("$base/document/other"))
        assertEquals(Route.Blocked, OfficeRequests.route("not a url"))
    }
}
