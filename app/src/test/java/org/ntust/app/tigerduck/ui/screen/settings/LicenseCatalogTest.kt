package org.ntust.app.tigerduck.ui.screen.settings

import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Developer
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.entity.License
import com.mikepenz.aboutlibraries.entity.Organization
import com.mikepenz.aboutlibraries.entity.Scm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LicenseCatalogTest {

    private val apache = License("Apache License 2.0", null, spdxId = "Apache-2.0", licenseContent = "apache", hash = "Apache-2.0")
    private val bsd = License("BSD 3-Clause", null, spdxId = "BSD-3-Clause", licenseContent = "bsd", hash = "BSD-3-Clause")

    private fun lib(
        id: String,
        licenses: Set<License> = setOf(apache),
        website: String? = null,
        scm: String? = null,
        org: String? = null,
        developers: List<String> = emptyList(),
    ) = Library(
        uniqueId = id,
        artifactVersion = "1.0",
        name = id.substringAfter(':'),
        description = null,
        website = website,
        developers = developers.map { Developer(it, null) },
        organization = org?.let { Organization(it, null) },
        scm = scm?.let { Scm(null, null, it) },
        licenses = licenses,
    )

    @Test
    fun `artifacts of one Maven group under the same licences share a row`() {
        val groups = LicenseCatalog.group(
            listOf(lib("androidx.activity:activity"), lib("androidx.activity:activity-compose")),
        )
        assertEquals(1, groups.size)
        assertEquals("androidx.activity", groups.single().title)
        assertEquals(
            listOf("androidx.activity:activity", "androidx.activity:activity-compose"),
            groups.single().artifacts.map { it.uniqueId },
        )
    }

    @Test
    fun `a group splits where its artifacts carry different licences`() {
        val groups = LicenseCatalog.group(
            listOf(
                lib("androidx.datastore:datastore"),
                lib("androidx.datastore:datastore-preferences-external-protobuf", licenses = setOf(bsd)),
            ),
        )
        assertEquals(listOf("androidx.datastore", "androidx.datastore"), groups.map { it.title })
        assertEquals(listOf(listOf(apache), listOf(bsd)), groups.map { it.licenses })
    }

    @Test
    fun `rows are ordered by title regardless of case`() {
        val groups = LicenseCatalog.group(
            listOf(lib("org.jsoup:jsoup"), lib("com.google.code.gson:gson"), lib("androidx.core:core")),
        )
        assertEquals(listOf("androidx.core", "com.google.code.gson", "org.jsoup"), groups.map { it.title })
    }

    @Test
    fun `holders are the organisation then the developers, each named once`() {
        val group = LicenseCatalog.group(
            listOf(
                lib("com.squareup.okhttp3:okhttp", org = "Square, Inc.", developers = listOf("Square, Inc.")),
                lib("com.squareup.okhttp3:logging-interceptor", developers = listOf("Jesse Wilson")),
            ),
        ).single()
        assertEquals(listOf("Square, Inc.", "Jesse Wilson"), group.holders)
    }

    @Test
    fun `the website falls back to the source repository`() {
        val group = LicenseCatalog.group(
            listOf(lib("org.jsoup:jsoup", scm = "https://github.com/jhy/jsoup")),
        ).single()
        assertEquals("https://github.com/jhy/jsoup", group.website)
    }

    @Test
    fun `the fdroid build lists nothing from Google Play`() {
        val google = listOf(
            "com.google.firebase",
            "com.google.android.gms",
            "com.google.android.play",
            "com.google.android.datatransport",
        )
        val titles = shipped("fdroid").map { it.title }
        assertTrue(titles.filter { t -> google.any { t.startsWith(it) } }.toString(), titles.none { t -> google.any { t.startsWith(it) } })
        // The play list is the one that does carry them, so the check above is looking in the right place.
        assertTrue(shipped("play").any { it.title == "com.google.firebase" })
    }

    @Test
    fun `every open-source licence either flavour ships carries its full text`() {
        // Google's SDK terms are not open-source licences and publish no text
        // to embed; the page links to them instead.
        val linkedOnly = setOf("ASDKL", "PCSDKToS")
        for (flavor in listOf("play", "fdroid")) {
            val missing = shipped(flavor)
                .flatMap { it.licenses }
                .filter { it.hash !in linkedOnly && it.licenseContent.isNullOrBlank() }
                .map { it.name }
                .toSet()
            assertTrue("$flavor: $missing", missing.isEmpty())
        }
    }

    @Test
    fun `reflow joins the hard-wrapped lines of a paragraph and keeps paragraphs apart`() {
        assertEquals(
            "Everyone is permitted to copy and distribute verbatim copies.\n\nPreamble",
            LicenseCatalog.reflow(" Everyone is permitted to copy\n and distribute verbatim copies.\n\n                            Preamble\n"),
        )
    }

    @Test
    fun `reflow keeps each list item on a line of its own`() {
        assertEquals(
            "provided that:\n(a) You must give a copy; and\n(b) You must cause files\n- one\n* two\n2. Grant",
            LicenseCatalog.reflow(
                "provided that:\n      (a) You must give\n          a copy; and\n      (b) You must cause\n          files\n- one\n* two\n2. Grant",
            ),
        )
    }

    @Test
    fun `reflow reads Windows line endings`() {
        assertEquals("a b\n\nc", LicenseCatalog.reflow("a\r\nb\r\n\r\nc"))
    }

    private fun shipped(flavor: String): List<LicenseGroup> {
        val json = File("src/$flavor/res/raw/aboutlibraries.json").readText()
        return LicenseCatalog.group(Libs.Builder().withJson(json).build().libraries)
    }
}
