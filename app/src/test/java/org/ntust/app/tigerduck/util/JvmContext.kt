package org.ntust.app.tigerduck.util

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * A [Context] for plain JVM tests of code that keeps only SharedPreferences and
 * files, so they need no Robolectric sandbox. Each preferences file is held in
 * memory, one per name as Android keeps them, and the app's folders are made in
 * a folder of this context's own as they are asked for. Make one per test, as
 * Robolectric makes an Application per test.
 *
 * Nothing else is real: any other call reaches android.jar's stub and gets its
 * default value back (`isReturnDefaultValues`), so a test whose code needs more
 * of Android stays on Robolectric.
 */
class JvmContext : ContextWrapper(null) {

    private val preferences = HashMap<String, InMemorySharedPreferences>()

    private val root: File by lazy { Files.createTempDirectory(folders, "app").toFile() }

    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        synchronized(preferences) { preferences.getOrPut(name) { InMemorySharedPreferences() } }

    override fun deleteSharedPreferences(name: String): Boolean {
        synchronized(preferences) { preferences.remove(name) }
        return true
    }

    override fun getDataDir(): File = root

    override fun getFilesDir(): File = folder("files")

    override fun getCacheDir(): File = folder("cache")

    override fun getNoBackupFilesDir(): File = folder("no_backup")

    override fun getDir(name: String, mode: Int): File = folder("app_$name")

    private fun folder(name: String): File = File(root, name).apply { mkdirs() }

    private companion object {
        /** Every context's folder, in one temporary folder deleted as the test JVM exits. */
        val folders: Path by lazy {
            Files.createTempDirectory("jvm-context").also { folder ->
                Runtime.getRuntime().addShutdownHook(Thread { folder.toFile().deleteRecursively() })
            }
        }
    }
}

/**
 * SharedPreferences in memory, with Android's own rules: a read of a key stored
 * as another type throws [ClassCastException], an editor's `clear()` goes before
 * its puts and removals whatever their order, a null value removes the key, and
 * a stored string set is a copy of the one put. Listeners are not supported, as
 * nothing in the app registers one.
 */
private class InMemorySharedPreferences : SharedPreferences {

    private val values = HashMap<String?, Any>()

    @Suppress("UNCHECKED_CAST")
    override fun getAll(): MutableMap<String, *> = synchronized(this) { HashMap(values) as MutableMap<String, *> }

    override fun getString(key: String?, defValue: String?): String? =
        synchronized(this) { values[key] as String? } ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        synchronized(this) { values[key] as MutableSet<String>? } ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = synchronized(this) { values[key] as Int? } ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = synchronized(this) { values[key] as Long? } ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float =
        synchronized(this) { values[key] as Float? } ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        synchronized(this) { values[key] as Boolean? } ?: defValue

    override fun contains(key: String?): Boolean = synchronized(this) { values.containsKey(key) }

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) =
        throw UnsupportedOperationException("JvmContext's preferences call no listeners; test this on Robolectric")

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) =
        throw UnsupportedOperationException("JvmContext's preferences call no listeners; test this on Robolectric")

    private inner class Editor : SharedPreferences.Editor {
        /** Each key's new value, or [Removed]. */
        private val changes = HashMap<String?, Any>()
        private var clear = false

        private fun change(key: String?, value: Any?): SharedPreferences.Editor {
            synchronized(this) { changes[key] = value ?: Removed }
            return this
        }

        override fun putString(key: String?, value: String?) = change(key, value)

        override fun putStringSet(key: String?, values: MutableSet<String>?) = change(key, values?.let(::HashSet))

        override fun putInt(key: String?, value: Int) = change(key, value)

        override fun putLong(key: String?, value: Long) = change(key, value)

        override fun putFloat(key: String?, value: Float) = change(key, value)

        override fun putBoolean(key: String?, value: Boolean) = change(key, value)

        override fun remove(key: String?) = change(key, null)

        override fun clear(): SharedPreferences.Editor {
            synchronized(this) { clear = true }
            return this
        }

        override fun commit(): Boolean {
            val (clearFirst, edits) = synchronized(this) {
                (clear to HashMap(changes)).also {
                    clear = false
                    changes.clear()
                }
            }
            synchronized(this@InMemorySharedPreferences) {
                if (clearFirst) values.clear()
                for ((key, value) in edits) if (value === Removed) values.remove(key) else values[key] = value
            }
            return true
        }

        override fun apply() {
            commit()
        }
    }

    private object Removed
}
