package org.ntust.app.tigerduck.ui.screen.settings

import android.content.Context
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

/**
 * One row on [OpenSourceLicensesScreen]: the artifacts of one Maven group
 * that ship under the same licences. AndroidX alone is over a hundred
 * artifacts, and listing each would bury the handful of projects that are not
 * AndroidX.
 */
data class LicenseGroup(
    /** The Maven group, e.g. `androidx.activity`. Not unique: a group whose artifacts differ in licence is split. */
    val title: String,
    val artifacts: List<Library>,
    val licenses: List<License>,
    /** POM organisation and developers, the nearest the build metadata comes to a copyright holder. */
    val holders: List<String>,
    val website: String?,
)

/** TigerDuck's own licence plus every third-party one the running flavor ships. */
data class Licenses(
    /** The repository's `LICENSE`, copied into the assets at build time. */
    val appLicense: String,
    val groups: List<LicenseGroup>,
)

object LicenseCatalog {
    fun group(libraries: List<Library>): List<LicenseGroup> =
        libraries
            .groupBy { it.uniqueId.substringBefore(':') to it.licenses.map(License::hash).sorted() }
            .map { (key, members) ->
                val artifacts = members.sortedBy { it.uniqueId }
                LicenseGroup(
                    title = key.first,
                    artifacts = artifacts,
                    licenses = artifacts.first().licenses.toList(),
                    holders = (
                        artifacts.mapNotNull { it.organization?.name } +
                            artifacts.flatMap { lib -> lib.developers.mapNotNull { it.name } }
                        )
                        .filter { it.isNotBlank() }
                        .distinct(),
                    website = artifacts.firstNotNullOfOrNull { it.website?.takeIf(String::isNotBlank) }
                        ?: artifacts.firstNotNullOfOrNull { it.scm?.url?.takeIf(String::isNotBlank) },
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })

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
 * Parses the flavor's generated `res/raw/aboutlibraries.json` once per
 * process: both licence screens read it, and it is ~150 KB.
 */
@Singleton
class LicenseRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()
    private var cached: Licenses? = null

    suspend fun load(): Licenses = mutex.withLock {
        cached ?: withContext(Dispatchers.IO) {
            val json = context.resources.openRawResource(R.raw.aboutlibraries)
                .bufferedReader().use { it.readText() }
            val appLicense = context.assets.open(APP_LICENSE_ASSET)
                .bufferedReader().use { it.readText() }
            Licenses(appLicense, LicenseCatalog.group(Libs.Builder().withJson(json).build().libraries))
        }.also { cached = it }
    }

    companion object {
        const val APP_LICENSE_ASSET = "LICENSE"
    }
}
