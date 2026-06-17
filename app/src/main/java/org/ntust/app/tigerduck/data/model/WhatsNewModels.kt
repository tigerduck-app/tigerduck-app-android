package org.ntust.app.tigerduck.data.model

/**
 * One localized "What's new" block, deserialized by Gson from
 * `assets/whatsnew.json`.
 *
 * Fields are nullable as defensive practice for a Gson-deserialized class
 * (see CLAUDE.md). This asset ships inside the APK and always matches the app
 * version, so the upgrade-crash pattern does not apply — but [WhatsNewContent]
 * still REQUIRES a `-keep` rule in `proguard-rules.pro` so R8 does not rename
 * its fields out from under Gson.
 */
data class WhatsNewContent(
    val title: String? = null,
    val highlights: List<String>? = null,
)
