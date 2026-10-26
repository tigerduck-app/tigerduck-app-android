package org.ntust.app.tigerduck.util

import android.content.Context

/**
 * The authority of the app's one FileProvider (AndroidManifest.xml), which
 * hands files to other apps: opened School Mail attachments and exported
 * class table images (`res/xml/file_provider_paths.xml`).
 *
 * Named for mail because mail was its first use. It stays that way: a URI
 * another app was granted before an update names this authority, and
 * renaming it would leave that URI unresolvable.
 */
fun fileProviderAuthority(context: Context): String = "${context.packageName}.mailfiles"
