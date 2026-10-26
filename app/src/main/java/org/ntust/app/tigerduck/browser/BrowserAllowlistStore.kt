package org.ntust.app.tigerduck.browser

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ntust.app.tigerduck.BuildConfig

/**
 * The allowlist the TigerDuck browser goes by, and the developer page's
 * changes to it: entries added on top of [BrowserAllowlist.DEFAULT_ENTRIES]
 * and the "Allow every site" switch.
 *
 * Stored as plain preferences (a string set and a boolean) in a file of its
 * own, which no sign-out clears: these are a developer's test settings, not a
 * student's data. A release build ([debug] false) reads none of it and always
 * goes by the defaults, whatever a debug build left in the file.
 */
class BrowserAllowlistStore(
    context: Context,
    private val debug: Boolean = BuildConfig.DEBUG,
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val state = MutableStateFlow(read())

    /** The allowlist now, which the developer page follows. */
    val allowlist: StateFlow<BrowserAllowlist> = state.asStateFlow()

    /** The entries the developer added, in the order shown. */
    val customEntries: List<AllowlistEntry>
        get() = if (debug) storedCustom() else emptyList()

    /** What [add] made of a line. */
    enum class AddResult { ADDED, INVALID, ALREADY_LISTED }

    /** Adds [text] as an entry; a default or one already added is not added twice. */
    fun add(text: String): AddResult {
        if (!debug) return AddResult.INVALID
        val entry = AllowlistEntry.parse(text) ?: return AddResult.INVALID
        if (entry in BrowserAllowlist.DEFAULT_ENTRIES || entry in storedCustom()) return AddResult.ALREADY_LISTED
        writeCustom(storedCustom() + entry)
        return AddResult.ADDED
    }

    /** Removes an entry the developer added; a default stays. */
    fun remove(entry: AllowlistEntry) {
        if (!debug) return
        writeCustom(storedCustom() - entry)
    }

    /** Turns "Allow every site" on or off. */
    fun setAllowEverySite(allow: Boolean) {
        if (!debug) return
        prefs.edit().putBoolean(KEY_ALLOW_EVERY_SITE, allow).apply()
        state.value = read()
    }

    /** Back to the defaults: no added entries, "Allow every site" off. */
    fun reset() {
        prefs.edit().remove(KEY_CUSTOM).remove(KEY_ALLOW_EVERY_SITE).apply()
        state.value = read()
    }

    private fun read(): BrowserAllowlist {
        if (!debug) return BrowserAllowlist.DEFAULT
        return BrowserAllowlist(
            entries = BrowserAllowlist.DEFAULT_ENTRIES + storedCustom(),
            allowEverySite = prefs.getBoolean(KEY_ALLOW_EVERY_SITE, false),
        )
    }

    // A set keeps no order, so each line carries its position: "0|example.com".
    private fun storedCustom(): List<AllowlistEntry> =
        prefs.getStringSet(KEY_CUSTOM, null).orEmpty()
            .mapNotNull { line ->
                val at = line.indexOf('|')
                val order = line.substring(0, at.coerceAtLeast(0)).toIntOrNull() ?: return@mapNotNull null
                AllowlistEntry.parse(line.substring(at + 1))?.let { order to it }
            }
            .sortedBy { it.first }
            .map { it.second }
            .distinct()

    private fun writeCustom(entries: List<AllowlistEntry>) {
        val lines = entries.distinct().mapIndexed { i, entry -> "$i|${entry.text}" }.toSet()
        prefs.edit().putStringSet(KEY_CUSTOM, lines).apply()
        state.value = read()
    }

    companion object {
        const val PREFS_FILE = "tigerduck_browser_allowlist"
        private const val KEY_CUSTOM = "custom_entries"
        private const val KEY_ALLOW_EVERY_SITE = "allow_every_site"

        @Volatile private var instance: BrowserAllowlistStore? = null

        /** The app's one store, made on first use; links are opened from places Hilt does not reach. */
        fun of(context: Context): BrowserAllowlistStore =
            instance ?: synchronized(this) {
                instance ?: BrowserAllowlistStore(context.applicationContext).also { instance = it }
            }
    }
}
