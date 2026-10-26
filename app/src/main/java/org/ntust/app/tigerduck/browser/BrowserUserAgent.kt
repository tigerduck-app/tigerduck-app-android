package org.ntust.app.tigerduck.browser

/** The user agent the browser sends for "Desktop site". */
object BrowserUserAgent {
    private val ANDROID_PLATFORM = Regex("""\(Linux; Android[^)]*\)""")

    /**
     * [mobile], the WebView's own user agent, as a desktop Chrome's on Linux:
     * the Android platform swapped for `X11; Linux x86_64` and `Mobile`
     * dropped, so a site serves its desktop layout. The WebView's `wv` mark
     * goes with the platform.
     */
    fun desktop(mobile: String): String =
        mobile.replace(ANDROID_PLATFORM, "(X11; Linux x86_64)")
            .replace(" Mobile", "")
            .replace(Regex("""\s+"""), " ")
            .trim()
}
