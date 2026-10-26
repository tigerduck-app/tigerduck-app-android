package org.ntust.app.tigerduck.library

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.shared.LibraryApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The last library QR code fetched in this process, kept so leaving the
 * Library page and coming back inside the code's lifetime shows the same code
 * at once instead of asking the library for another one. Mirrors the Apple
 * app's `LibraryQRCache` and `LibraryQRImageCache`.
 *
 * Memory only. A code belongs to the library account it was fetched for, read
 * as [org.ntust.app.tigerduck.shared.LibraryCredentialStore.libraryAccountGeneration],
 * and is shown again only for that account; [LibrarySession] clears it once no
 * library account is signed in. The rendered image is the larger part and
 * goes under memory pressure, leaving the code to be rendered again.
 *
 * [Image] is the rendered code, a [Bitmap] in the app ([LibraryQRBitmapCache]).
 */
open class LibraryQRCache<Image : Any> {

    class Entry<Image : Any>(
        val payload: String,
        /** When the code was issued, on the caller's monotonic clock in ms. */
        val issuedAt: Long,
        val account: Int,
        val image: Image?,
    )

    private var entry: Entry<Image>? = null

    @Synchronized
    fun store(payload: String, image: Image?, account: Int, issuedAt: Long) {
        entry = Entry(payload, issuedAt, account, image)
    }

    /**
     * The code for [account] while it has at least [REUSE_THRESHOLD_SECONDS]
     * left at [now], with the whole seconds it has left.
     */
    @Synchronized
    fun reusable(account: Int, now: Long): Pair<Entry<Image>, Int>? {
        val e = entry ?: return null
        if (e.account != account) return null
        val left = secondsLeft(e.issuedAt, now)
        return if (left >= REUSE_THRESHOLD_SECONDS) e to left else null
    }

    @Synchronized
    fun clear() {
        entry = null
    }

    /** Drops the rendered image and keeps the code, which renders again on its next showing. */
    @Synchronized
    fun dropImage() {
        entry = entry?.let { Entry(it.payload, it.issuedAt, it.account, null) }
    }

    companion object {
        /** How long the library accepts a code after it was issued. */
        const val LIFETIME_SECONDS = LibraryApi.QR_VALID_SECONDS

        /** A cached code with at least this many seconds left is shown again. */
        const val REUSE_THRESHOLD_SECONDS = 15

        /** Whole seconds left at [now] for a code issued at [issuedAt]; a clock behind it counts as none. */
        fun secondsLeft(issuedAt: Long, now: Long): Int {
            val elapsed = now - issuedAt
            if (elapsed < 0) return 0
            return (LIFETIME_SECONDS - (elapsed / 1000).toInt()).coerceAtLeast(0)
        }
    }
}

/** [LibraryQRCache] for the phone's rendered codes, dropping the image when the system runs low on memory. */
@Singleton
class LibraryQRBitmapCache @Inject constructor(
    @ApplicationContext context: Context,
) : LibraryQRCache<Bitmap>() {
    init {
        context.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) dropImage()
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = dropImage()
        })
    }
}
