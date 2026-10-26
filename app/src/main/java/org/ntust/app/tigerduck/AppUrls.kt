package org.ntust.app.tigerduck

/**
 * The TigerDuck pages the app links to, in one place so every screen opens
 * the same address. The iPhone app keeps the same list in `AppURLs.swift`.
 */
object AppUrls {
    const val WEBSITE = "https://tigerduck.app"
    const val GITHUB = "https://github.com/tigerduck-app"
    const val PRIVACY_POLICY = "https://tigerduck.app/privacy-policy"
    const val DELETE_ACCOUNT = "https://tigerduck.app/delete-account"

    /** The website's TigerSync page: what it is and what it keeps. */
    const val LEARN_MORE_TIGERSYNC = "https://tigerduck.app/tigersync"

    /** The Android app's issue tracker, where About > Others sends feedback. */
    const val ISSUES = "https://github.com/tigerduck-app/tigerduck-app-android/issues"

    /**
     * The share links' host (`ClassTableShareCode.SHARE_ROOT`): its page opens the app, or sends
     * someone without it to the store.
     */
    const val SHARE = "https://share.tigerduck.app"

    /** Public uptime page for every TigerDuck-facing service. */
    const val SERVER_STATUS = "https://status.tigerduck.app/"
}
