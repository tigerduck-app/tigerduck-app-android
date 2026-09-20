package org.ntust.app.tigerduck.ui.screen.settings

import android.content.Context
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
    val texts: List<LicenseText>,
    val notices: List<BundledNotice>,
)

/** TigerDuck's own licence plus every third-party one the running flavor ships. */
data class Licenses(
    /** The repository's `LICENSE`, copied into the assets at build time. */
    val appLicense: String,
    val entries: List<LicenseEntry>,
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
    fun parseNotices(json: String): Map<String, List<BundledNotice>> {
        val root = JsonParser.parseString(json).asJsonObject
        val texts = root.getAsJsonObject("texts")
        return root.getAsJsonObject("libraries").entrySet().associate { (library, refs) ->
            library to refs.asJsonArray.mapNotNull { ref ->
                val at = ref.asJsonObject
                val content = texts[at["hash"]?.asString]?.asString ?: return@mapNotNull null
                BundledNotice(at["name"]?.asString ?: content.substringBefore('\n'), content)
            }
        }
    }

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
        val entries = root.getAsJsonArray("entries").map { element ->
            val entry = element.asJsonObject
            fun field(name: String) = entry[name]?.takeIf { !it.isJsonNull }?.asString
            LicenseEntry(
                title = field("title").orEmpty(),
                licenseNames = listOfNotNull(field("spdxId") ?: field("licenseName")),
                artifacts = emptyList(),
                holders = entry.strings("holders"),
                website = field("website"),
                note = field("note"),
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
        }
        val holders = root.getAsJsonObject("holders").entrySet()
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
        cached ?: withContext(Dispatchers.IO) {
            val libraries = Libs.Builder().withJson(raw(R.raw.aboutlibraries)).build().libraries
            Licenses(
                appLicense = asset(APP_LICENSE_ASSET),
                entries = LicenseCatalog.entries(
                    libraries,
                    LicenseCatalog.parseNotices(raw(R.raw.bundled_notices)),
                    extraLicenses(libraries),
                ),
            )
        }.also { cached = it }
    }

    private fun raw(id: Int): String =
        context.resources.openRawResource(id).bufferedReader().use { it.readText() }

    private fun asset(name: String): String =
        context.assets.open(name).bufferedReader().use { it.readText() }

    private fun extraLicenses(libraries: List<Library>): ExtraLicenses {
        val bySpdxId = libraries.flatMap { it.licenses }
            .mapNotNull { license -> license.spdxId?.let { it to license.licenseContent } }
            .toMap()
        return LicenseCatalog.parseExtras(raw(R.raw.extra_licenses), bySpdxId::get, ::asset)
    }

    companion object {
        const val APP_LICENSE_ASSET = "tigerduck-license.txt"
    }
}
