package org.ntust.app.tigerduck.wear

import org.ntust.app.tigerduck.R

/**
 * The watch app's licence lists, exported from `:wear`'s dependencies into
 * this flavor's resources. The watch shows no licences itself — it declares
 * `standalone = false`, so it never reaches a user without the phone app,
 * and a watch face is a poor place to read one. See wear/build.gradle.kts.
 */
object WearLicenses {
    val libraries: Int? = R.raw.aboutlibraries_wear
    val notices: Int? = R.raw.bundled_notices_wear
}
