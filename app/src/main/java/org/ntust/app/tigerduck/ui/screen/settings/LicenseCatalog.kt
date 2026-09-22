package org.ntust.app.tigerduck.ui.screen.settings

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.entity.License
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.wear.WearLicenses
import javax.inject.Inject
import javax.inject.Singleton

/** One licence, shown in full, or linked out when the licensor publishes no text to embed. */
data class LicenseText(val name: String, val content: String?, val url: String?)

/**
 * A notice a dependency ships inside its own artifact. A list built from
 * POMs cannot see these: Apache-2.0 section 4(d) notices, the filled-in
 * copyright line for licences whose published text is the SPDX template,
 * and the licences of the third-party code compiled into Google's closed
 * SDKs.
 */
data class BundledNotice(val name: String, val content: String)

/**
 * What [LicenseCatalog.parseNotices] read out of the generated documents.
 *
 * [unresolved] is the point of the type. Texts are stored by content hash
 * across documents so `bundled_notices_wear.json` can leave out every one
 * the phone's document already carries — which also means a phone-side
 * dependency change can remove a text the watch still points at. A
 * reference that resolves to nothing drops its notice off the page with
 * nothing left behind to notice, so the parser carries the misses out
 * rather than swallowing them.
 */
data class BundledNotices(
    /** The notices each `group:artifact` carries inside its own artifact. */
    val byLibrary: Map<String, List<BundledNotice>> = emptyMap(),
    /** Every hash the documents named, resolved or not. */
    val references: Int = 0,
    /** `library: hash`, one per reference no document carried a text for. */
    val unresolved: List<String> = emptyList(),
)

/**
 * One row on [OpenSourceLicensesScreen]: either the artifacts of one Maven
 * group that ship under the same licences, or one piece of third-party
 * material that no POM describes. AndroidX alone is over a hundred
 * artifacts, and listing each would bury the handful of projects that are
 * not AndroidX.
 */
data class LicenseEntry(
    /** The Maven group, e.g. `androidx.activity`, or the material's own name. */
    val title: String,
    val licenseNames: List<String>,
    /** `group:artifact version`. Empty for material that isn't a Maven artifact. */
    val artifacts: List<String>,
    val holders: List<String>,
    val website: String?,
    /** Why this ships, for entries the dependency graph doesn't explain on its own. */
    val note: String?,
    /** TigerDuck's own, published separately: listed beside the app, not under third parties. */
    val firstParty: Boolean = false,
    val texts: List<LicenseText>,
    val notices: List<BundledNotice>,
)

/** TigerDuck's own licence plus every third-party one the running flavor ships. */
data class Licenses(
    /** The repository's `LICENSE`, copied into the assets at build time. */
    val appLicense: String,
    val entries: List<LicenseEntry>,
    /**
     * The watch app's, which the phone shows because the watch has no page
     * of its own: it declares `standalone = false`, so it never reaches a
     * user without the phone app. Empty on fdroid, which ships no watch app.
     */
    val wearEntries: List<LicenseEntry> = emptyList(),
)

object LicenseCatalog {
    fun entries(
        libraries: List<Library>,
        notices: Map<String, List<BundledNotice>> = emptyMap(),
        extras: ExtraLicenses = ExtraLicenses(),
    ): List<LicenseEntry> =
        (mavenEntries(libraries, notices, extras) + extras.entries)
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })

    private fun mavenEntries(
        libraries: List<Library>,
        notices: Map<String, List<BundledNotice>>,
        extras: ExtraLicenses,
    ): List<LicenseEntry> =
        libraries
            .groupBy { it.uniqueId.substringBefore(':') to it.licenses.map(License::hash).sorted() }
            .map { (key, members) ->
                val artifacts = members.sortedBy { it.uniqueId }
                val licenses = artifacts.first().licenses.toList()
                LicenseEntry(
                    title = key.first,
                    licenseNames = licenses.map { it.spdxId ?: it.name },
                    artifacts = artifacts.map { artifact ->
                        listOfNotNull(artifact.uniqueId, artifact.artifactVersion).joinToString(" ")
                    },
                    // The published metadata's organisation and developers,
                    // then any copyright line it omits altogether.
                    holders = (
                        artifacts.mapNotNull { it.organization?.name } +
                            artifacts.flatMap { lib -> lib.developers.mapNotNull { it.name } } +
                            artifacts.flatMap { extras.holders[it.uniqueId].orEmpty() }
                        )
                        .filter { it.isNotBlank() }
                        .distinct(),
                    website = artifacts.firstNotNullOfOrNull { it.website?.takeIf(String::isNotBlank) }
                        ?: artifacts.firstNotNullOfOrNull { it.scm?.url?.takeIf(String::isNotBlank) },
                    note = null,
                    texts = licenses.map { LicenseText(it.name, it.licenseContent, it.url) },
                    notices = artifacts.flatMap { notices[it.uniqueId].orEmpty() }.distinct(),
                )
            }

    /**
     * The generated notices, keyed by artifact. Read off the JSON tree
     * rather than into a class: R8 rewrites a class that is only ever
     * instantiated by reflection into an abstract one, and Gson then cannot
     * construct it — the page crashed in a minified build for exactly that
     * reason.
     */
    fun parseNotices(vararg documents: String): BundledNotices {
        val roots = documents.map { JsonParser.parseString(it).asJsonObject }
        // Texts are keyed by content hash across every document, so the
        // watch's can leave out the ones the phone's already carries — the
        // two overlap almost entirely, both shipping Play Services.
        val texts = roots.flatMap { it.getAsJsonObject("texts")?.entrySet().orEmpty() }
            .associate { (hash, text) -> hash to text.asString }
        val notices = mutableMapOf<String, MutableList<BundledNotice>>()
        var references = 0
        val unresolved = mutableListOf<String>()
        for (root in roots) {
            for ((library, refs) in root.getAsJsonObject("libraries")?.entrySet().orEmpty()) {
                for (ref in refs.asJsonArray) {
                    references++
                    val at = ref.asJsonObject
                    val hash = at["hash"]?.asString
                    val content = hash?.let(texts::get)
                    if (content == null) {
                        unresolved.add("$library: $hash")
                        continue
                    }
                    val notice = BundledNotice(at["name"]?.asString ?: content.substringBefore('\n'), content)
                    val carried = notices.getOrPut(library) { mutableListOf() }
                    if (notice !in carried) carried.add(notice)
                }
            }
        }
        return BundledNotices(notices, references, unresolved)
    }

    /**
     * The notices of one entry, one section per distinct text, titled with
     * every name that refers to it. Play Services references the same
     * 11 KB Google text under sixteen names — Dagger, Guava, Firebase, the
     * coroutines runtime — and repeating the body under each of them put
     * around half a megabyte of text on that one detail page. Every name is
     * a legal attribution and none is dropped; only the repetition is.
     */
    fun groupNotices(notices: List<BundledNotice>): List<BundledNotice> =
        notices.groupBy(BundledNotice::content)
            .map { (content, sharing) ->
                BundledNotice(sharing.map(BundledNotice::name).distinct().joinToString(", "), content)
            }

    /**
     * The text of a licence the library list already carries, for an extras
     * entry that names one by `licenseRef`.
     *
     * The first entry that has a text, not the last that matches: the export
     * already keys an EPL/EDL pair by hash with no `spdxId` on one of them,
     * so a second entry under a duplicate spdxId carrying no text is a
     * plausible future export, and last-wins would quietly strip the licence
     * off whichever extras entry points at it.
     */
    fun resolveLicenseRef(licenses: List<License>, spdxId: String): String? =
        licenses.firstOrNull { it.spdxId == spdxId && !it.licenseContent.isNullOrBlank() }?.licenseContent

    /**
     * The hand-maintained extras. [licenseText] resolves a `licenseRef` to a
     * licence already carried by the library list, and [asset] a `textAsset`
     * to the file the build copied in.
     */
    fun parseExtras(
        json: String,
        licenseText: (String) -> String?,
        asset: (String) -> String,
    ): ExtraLicenses {
        val root = JsonParser.parseString(json).asJsonObject
        val entries = root.getAsJsonArray("entries")?.map { element ->
            val entry = element.asJsonObject
            fun field(name: String) = entry[name]?.takeIf { !it.isJsonNull }?.asString
            LicenseEntry(
                title = field("title").orEmpty(),
                licenseNames = listOfNotNull(field("spdxId") ?: field("licenseName")),
                artifacts = emptyList(),
                holders = entry.strings("holders"),
                website = field("website"),
                note = field("note"),
                firstParty = entry["firstParty"]?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
                texts = listOf(
                    LicenseText(
                        name = field("licenseName") ?: field("spdxId").orEmpty(),
                        content = field("text")
                            ?: field("textAsset")?.let(asset)
                            ?: field("licenseRef")?.let(licenseText),
                        url = field("website"),
                    ),
                ),
                notices = emptyList(),
            )
        }.orEmpty()
        val holders = root.getAsJsonObject("holders")?.entrySet().orEmpty()
            .associate { (library, lines) -> library to lines.asJsonArray.map { it.asString } }
        return ExtraLicenses(entries, holders)
    }

    private fun JsonObject.strings(name: String): List<String> =
        getAsJsonArray(name)?.map { it.asString }.orEmpty()

    private val listItem = Regex("""^([-*•]|\(?[0-9a-zA-Z]{1,3}[.)])\s""")

    /**
     * Licence files are hard-wrapped at ~72 columns, which on a phone breaks
     * every line a second time. Joins the lines of each paragraph so the
     * text wraps to the screen, keeping blank lines between paragraphs and
     * list items — `(a)`, `1.`, `-` — on lines of their own. Only whitespace
     * changes; every word stays as written.
     */
    fun reflow(text: String): String =
        text.replace("\r\n", "\n")
            .trim()
            .split(Regex("""\n\s*\n"""))
            .joinToString("\n\n") { paragraph ->
                paragraph.lines()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .fold(mutableListOf<String>()) { out, line ->
                        if (out.isEmpty() || listItem.containsMatchIn(line)) {
                            out.add(line)
                        } else {
                            out[out.lastIndex] = out.last() + " " + line
                        }
                        out
                    }
                    .joinToString("\n")
            }
}

/**
 * `res/raw/extra_licenses.json`: third-party material that ships without a
 * POM to describe it, and copyright lines the published metadata leaves out.
 */
data class ExtraLicenses(
    val entries: List<LicenseEntry> = emptyList(),
    val holders: Map<String, List<String>> = emptyMap(),
)

/**
 * Parses the flavor's generated lists once per process: both licence screens
 * read them, and together they run to several hundred kilobytes.
 */
@Singleton
class LicenseRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()
    private var cached: Licenses? = null

    suspend fun load(): Licenses = mutex.withLock {
        cached ?: withContext(Dispatchers.IO) { read() }.also { cached = it }
    }

    /**
     * Never throws. The view model collects this through
     * `stateIn(..., Eagerly)`, where an exception has nowhere to go but the
     * uncaught handler — a malformed or truncated export would take the
     * whole process down from a screen the user has not opened yet.
     *
     * Each input falls back on its own, because they are not equally
     * trustworthy and the result is cached for the process. The phone's
     * library list is generated and diffed by CI; `extra_licenses.json` is
     * hand-maintained, and the watch's two documents only exist on play. One
     * hand-edit, or one watch document that will not parse, must not take
     * the eighty rows beside it down — nor the app's own licence, which
     * comes from an asset and is read separately.
     */
    private fun read(): Licenses {
        val appLicense = readOrWarn("the app's own licence") { asset(APP_LICENSE_ASSET) }.orEmpty()
        val libraries = readOrWarn("the library list") {
            Libs.Builder().withJson(raw(R.raw.aboutlibraries)).build().libraries
        }.orEmpty()
        val notices = readOrWarn("the bundled notices") {
            LicenseCatalog.parseNotices(
                raw(R.raw.bundled_notices),
                *listOfNotNull(WearLicenses.notices?.let(::raw)).toTypedArray(),
            ).byLibrary
        }.orEmpty()
        val extras = readOrWarn("the hand-maintained extras") { extraLicenses(libraries) }
            ?: ExtraLicenses()
        val wearLibraries = readOrWarn("the watch's library list") {
            WearLicenses.libraries?.let { Libs.Builder().withJson(raw(it)).build().libraries }
        }.orEmpty()
        return Licenses(
            appLicense = appLicense,
            entries = readOrWarn("the licence entries") {
                LicenseCatalog.entries(libraries, notices, extras)
            }.orEmpty(),
            // The standalone rows are the phone's own; only the
            // copyright lines the metadata omits carry over.
            wearEntries = readOrWarn("the watch's licence entries") {
                LicenseCatalog.entries(wearLibraries, notices, ExtraLicenses(holders = extras.holders))
            }.orEmpty(),
        )
    }

    /** [block], or null and a line in the log — never an exception. */
    private fun <T> readOrWarn(what: String, block: () -> T): T? =
        runCatching(block).onFailure { Log.w(TAG, "Could not read $what", it) }.getOrNull()

    private fun raw(id: Int): String =
        context.resources.openRawResource(id).bufferedReader().use { it.readText() }

    private fun asset(name: String): String =
        context.assets.open(name).bufferedReader().use { it.readText() }

    private fun extraLicenses(libraries: List<Library>): ExtraLicenses {
        val licenses = libraries.flatMap { it.licenses }
        return LicenseCatalog.parseExtras(
            raw(R.raw.extra_licenses),
            { spdxId -> LicenseCatalog.resolveLicenseRef(licenses, spdxId) },
            ::asset,
        )
    }

    companion object {
        const val APP_LICENSE_ASSET = "tigerduck-license.txt"
        private const val TAG = "LicenseRepository"
    }
}
