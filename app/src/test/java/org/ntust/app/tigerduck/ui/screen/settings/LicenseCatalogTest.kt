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
        val entries = LicenseCatalog.entries(
            listOf(lib("androidx.activity:activity"), lib("androidx.activity:activity-compose")),
        )
        assertEquals(1, entries.size)
        assertEquals("androidx.activity", entries.single().title)
        assertEquals(
            listOf("androidx.activity:activity 1.0", "androidx.activity:activity-compose 1.0"),
            entries.single().artifacts,
        )
    }

    @Test
    fun `a group splits where its artifacts carry different licences`() {
        val entries = LicenseCatalog.entries(
            listOf(
                lib("androidx.datastore:datastore"),
                lib("androidx.datastore:datastore-preferences-external-protobuf", licenses = setOf(bsd)),
            ),
        )
        assertEquals(listOf("androidx.datastore", "androidx.datastore"), entries.map { it.title })
        assertEquals(listOf(listOf("Apache-2.0"), listOf("BSD-3-Clause")), entries.map { it.licenseNames })
    }

    @Test
    fun `rows are ordered by title regardless of case`() {
        val entries = LicenseCatalog.entries(
            listOf(lib("org.jsoup:jsoup"), lib("com.google.code.gson:gson"), lib("androidx.core:core")),
        )
        assertEquals(listOf("androidx.core", "com.google.code.gson", "org.jsoup"), entries.map { it.title })
    }

    @Test
    fun `holders are the organisation then the developers, each named once`() {
        val entry = LicenseCatalog.entries(
            listOf(
                lib("com.squareup.okhttp3:okhttp", org = "Square, Inc.", developers = listOf("Square, Inc.")),
                lib("com.squareup.okhttp3:logging-interceptor", developers = listOf("Jesse Wilson")),
            ),
        ).single()
        assertEquals(listOf("Square, Inc.", "Jesse Wilson"), entry.holders)
    }

    @Test
    fun `a copyright line the metadata omits is added to the holders`() {
        val entry = LicenseCatalog.entries(
            libraries = listOf(lib("androidx.glance:glance-appwidget-external-protobuf", licenses = setOf(bsd), org = "The Android Open Source Project")),
            extras = ExtraLicenses(
                holders = mapOf("androidx.glance:glance-appwidget-external-protobuf" to listOf("Copyright 2008 Google Inc.")),
            ),
        ).single()
        assertEquals(listOf("The Android Open Source Project", "Copyright 2008 Google Inc."), entry.holders)
    }

    @Test
    fun `a notice bundled inside an artifact is attached to its row`() {
        val notice = BundledNotice("NOTICE.md", "the notice")
        val entry = LicenseCatalog.entries(
            libraries = listOf(lib("org.eclipse.angus:angus-mail")),
            notices = mapOf("org.eclipse.angus:angus-mail" to listOf(notice)),
        ).single()
        assertEquals(listOf(notice), entry.notices)
    }

    @Test
    fun `the website falls back to the source repository`() {
        val entry = LicenseCatalog.entries(
            listOf(lib("org.jsoup:jsoup", scm = "https://github.com/jhy/jsoup")),
        ).single()
        assertEquals("https://github.com/jhy/jsoup", entry.website)
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
        val linkedOnly = setOf("Android Software Development Kit License", "Play Core Software Development Kit Terms of Service")
        for (flavor in listOf("play", "fdroid")) {
            val missing = shipped(flavor)
                .flatMap { it.texts }
                .filter { it.name !in linkedOnly && it.content.isNullOrBlank() }
                .map { it.name }
                .toSet()
            assertTrue("$flavor: $missing", missing.isEmpty())
        }
    }

    @Test
    fun `material that ships without a POM to describe it is listed too`() {
        for (flavor in listOf("play", "fdroid")) {
            val titles = shipped(flavor).map { it.title }
            for (expected in listOf("name-abbr", "Public Suffix List", "Material Design Icons")) {
                assertTrue("$flavor is missing $expected", expected in titles)
            }
        }
    }

    @Test
    fun `each entry that ships without a POM says why it ships, and carries its licence`() {
        for (entry in shipped("play").filter { it.artifacts.isEmpty() }) {
            assertTrue("${entry.title} has no note", !entry.note.isNullOrBlank())
            assertTrue("${entry.title} has no holders", entry.holders.isNotEmpty())
            assertTrue("${entry.title} has no licence text", entry.texts.any { !it.content.isNullOrBlank() })
        }
    }

    @Test
    fun `the name-abbr row carries the submodule's own licence, not the app's`() {
        val entry = shipped("play").single { it.title == "name-abbr" }
        assertEquals(File("../name-abbr/LICENSE").readText(), entry.texts.single().content)
        assertTrue(entry.texts.single().content!!.startsWith("MIT License"))
    }

    @Test
    fun `a licence published as the SPDX template carries its real copyright somewhere`() {
        // MIT and the BSD family publish a template whose `<year> <copyright
        // holders>` line the artifact is expected to fill in. Both licences
        // require that notice be reproduced, so every library that reaches
        // the page with an unfilled one has to get it from somewhere else:
        // a notice bundled in the artifact, or a hand-supplied holder.
        val placeholder = Regex("""<(year|copyright holders?|owner)>""", RegexOption.IGNORE_CASE)
        for (flavor in listOf("play", "fdroid")) {
            val supplied = noticesOf(flavor).keys + extras().holders.keys
            val libraries = Libs.Builder().withJson(listJson(flavor)).build().libraries
            val unattributed = libraries
                .filter { library -> library.licenses.any { placeholder.containsMatchIn(it.licenseContent.orEmpty()) } }
                .map { it.uniqueId }
                .filterNot { it in supplied }
            assertTrue("$flavor: $unattributed", unattributed.isEmpty())
        }
    }

    @Test
    fun `every bundled notice resolves to a text`() {
        for (flavor in listOf("play", "fdroid")) {
            val notices = noticesOf(flavor)
            assertTrue("$flavor has no bundled notices at all", notices.isNotEmpty())
            for ((library, list) in notices) {
                assertTrue("$flavor: $library has an empty notice", list.all { it.content.isNotBlank() })
            }
        }
    }

    @Test
    fun `the licences inside Google's closed SDKs reach the play page`() {
        val playServices = shipped("play").single { it.title == "com.google.android.gms" }
        val names = playServices.notices.map { it.name }
        assertTrue(names.toString(), listOf("Guava JDK7", "Kotlin", "Protocol Buffers").any { it in names })
        // fdroid ships no Play Services at all, so it carries none of this.
        assertTrue(shipped("fdroid").none { it.title == "com.google.android.gms" })
    }

    @Test
    fun `the watch app's libraries are listed, and only in the flavour that ships it`() {
        val wear = wearShipped()
        assertTrue(wear.map { it.title }.toString(), wear.any { it.title == "androidx.wear.compose" })
        assertTrue(wear.any { it.title == "com.google.android.gms" })
        // fdroid has no watch app, so no list for one to read.
        assertTrue(!File("src/fdroid/res/raw/aboutlibraries_wear.json").exists())
        assertTrue(!File("src/fdroid/res/raw/bundled_notices_wear.json").exists())
    }

    @Test
    fun `the watch's notices are stored once, not repeated per document`() {
        val phone = File("src/play/res/raw/bundled_notices.json").readText()
        val wear = File("src/play/res/raw/bundled_notices_wear.json").readText()
        // Both ship Play Services, so the watch document leans on the
        // phone's texts by hash rather than carrying its own copies.
        assertTrue("the watch document is carrying duplicate texts", wear.length < phone.length / 4)
        // And every reference still resolves once the two are read together.
        for ((library, notices) in LicenseCatalog.parseNotices(phone, wear)) {
            assertTrue("$library has an unresolved notice", notices.all { it.content.isNotBlank() })
        }
    }

    @Test
    fun `a licence published as the SPDX template carries its real copyright on the watch too`() {
        val placeholder = Regex("""<(year|copyright holders?|owner)>""", RegexOption.IGNORE_CASE)
        val supplied = noticesOf("play").keys + extras().holders.keys
        val unattributed = Libs.Builder()
            .withJson(File("src/play/res/raw/aboutlibraries_wear.json").readText()).build().libraries
            .filter { library -> library.licenses.any { placeholder.containsMatchIn(it.licenseContent.orEmpty()) } }
            .map { it.uniqueId }
            .filterNot { it in supplied }
        assertTrue("$unattributed", unattributed.isEmpty())
    }

    @Test
    fun `name-abbr sits beside the app, not under third parties`() {
        val entries = shipped("play")
        assertEquals(listOf("name-abbr"), entries.filter { it.firstParty }.map { it.title })
        // The two that genuinely are third-party stay where they belong.
        assertTrue(entries.filterNot { it.firstParty }.map { it.title }.containsAll(
            listOf("Public Suffix List", "Material Design Icons"),
        ))
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

    // The committed resources, read the way LicenseRepository reads them,
    // so these tests fail on a stale export rather than at runtime.

    private fun listJson(flavor: String) = File("src/$flavor/res/raw/aboutlibraries.json").readText()

    private fun shipped(flavor: String): List<LicenseEntry> =
        LicenseCatalog.entries(
            Libs.Builder().withJson(listJson(flavor)).build().libraries,
            noticesOf(flavor),
            extras(),
        )

    private fun noticesOf(flavor: String): Map<String, List<BundledNotice>> =
        LicenseCatalog.parseNotices(
            *listOfNotNull(
                File("src/$flavor/res/raw/bundled_notices.json").readText(),
                File("src/$flavor/res/raw/bundled_notices_wear.json").takeIf { it.isFile }?.readText(),
            ).toTypedArray(),
        )

    /** The watch app's list, which only the play flavor carries. */
    private fun wearShipped(): List<LicenseEntry> =
        LicenseCatalog.entries(
            Libs.Builder().withJson(File("src/play/res/raw/aboutlibraries_wear.json").readText()).build().libraries,
            noticesOf("play"),
            ExtraLicenses(holders = extras().holders),
        )

    /** The production parser, with the two lookups the app resolves at run time. */
    private fun extras(): ExtraLicenses {
        val licenses = Libs.Builder().withJson(listJson("play")).build().libraries.flatMap { it.licenses }
        return LicenseCatalog.parseExtras(
            File("src/main/res/raw/extra_licenses.json").readText(),
            licenseText = { spdxId -> licenses.first { it.spdxId == spdxId }.licenseContent },
            // What copyLicenseAssets copies into the assets, read from source.
            asset = { name ->
                assertEquals("name-abbr-license.txt", name)
                File("../name-abbr/LICENSE").readText()
            },
        )
    }
}
