package org.ntust.app.tigerduck.util

import androidx.core.content.FileProvider

/**
 * Makes the FileProvider read its roots again. It keeps the roots it read first for the rest of
 * the process, while each Robolectric test has an app folder of its own, so the second test in a
 * run that hands out a file would be refused a URI for a file inside its own cache folder.
 */
fun forgetFileProviderRoots() {
    val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
    synchronized(cache.get(null)!!) { (cache.get(null) as MutableMap<*, *>).clear() }
}
