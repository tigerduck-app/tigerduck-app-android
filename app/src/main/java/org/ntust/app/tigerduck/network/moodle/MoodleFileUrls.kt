package org.ntust.app.tigerduck.network.moodle

import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * Where a file a Moodle answer lists downloads from, as the Moodle app fetches it: under
 * `tokenpluginfile.php` with the account's private access key in the path, else the web-service
 * path with the token. Either key only ever goes to the Moodle site. Ported from iOS
 * `MoodleFileTransfer.downloadURL`.
 */
object MoodleFileUrls {

    /**
     * [fileUrl] as the website serves it, with no key: the web-service path becomes the web one
     * and a token in it goes. The browser opens it with its own Moodle session.
     */
    fun webUrl(fileUrl: String): String? {
        val url = fileUrl.toHttpUrlOrNull() ?: return null
        if (!isMoodleFile(fileUrl)) return null
        val segments = url.pathSegments.let { if (it.firstOrNull() == "webservice") it.drop(1) else it }
        val builder = HttpUrl.Builder().scheme("https").host(url.host).port(url.port)
        segments.forEach { builder.addPathSegment(it) }
        for (i in 0 until url.querySize) {
            val name = url.queryParameterName(i)
            if (name != "token") builder.addQueryParameter(name, url.queryParameterValue(i))
        }
        return builder.build().toString()
    }

    /** Whether [fileUrl] is a file the Moodle site's `pluginfile.php` serves, which takes the key. */
    fun isMoodleFile(fileUrl: String): Boolean = downloadUrl(fileUrl, accessKey = null) != null

    /**
     * The request that downloads [fileUrl], or null for a file not served by the Moodle site's
     * `pluginfile.php`, which no key may be sent to. With the account's private access key, a GET
     * under `tokenpluginfile.php`, as the Moodle app downloads files. Without it, a POST to the
     * web-service path with [token] in the form, never in the URL, which access logs record;
     * Moodle reads the token from either.
     */
    fun downloadRequest(fileUrl: String, accessKey: String?, token: String): Request? {
        val url = downloadUrl(fileUrl, accessKey) ?: return null
        val request = Request.Builder().url(url)
        return if (accessKey.isNullOrEmpty()) {
            request.post(FormBody.Builder().add("token", token).build()).build()
        } else {
            request.get().build()
        }
    }

    /** [fileUrl] on the path [downloadRequest] takes, with the key in it when there is one. */
    private fun downloadUrl(fileUrl: String, accessKey: String?): String? {
        val url = fileUrl.toHttpUrlOrNull() ?: return null
        if (url.scheme != "https" || url.host.lowercase() != MoodleWebservice.SITE_HOST) return null
        val segments = url.pathSegments
        val rest: List<String> = when {
            segments.size > 2 && segments[0] == "webservice" && segments[1] == "pluginfile.php" ->
                segments.drop(2)
            segments.size > 1 && segments[0] == "pluginfile.php" -> segments.drop(1)
            else -> return null
        }
        val builder = HttpUrl.Builder().scheme("https").host(url.host).port(url.port)
        val key = accessKey?.takeIf { it.isNotEmpty() }
        if (key != null) {
            builder.addPathSegment("tokenpluginfile.php").addPathSegment(key)
        } else {
            builder.addPathSegment("webservice").addPathSegment("pluginfile.php")
        }
        rest.forEach { builder.addPathSegment(it) }
        for (i in 0 until url.querySize) {
            val name = url.queryParameterName(i)
            if (name != "token") builder.addQueryParameter(name, url.queryParameterValue(i))
        }
        return builder.build().toString()
    }
}

/**
 * The type a download of this file can be checked against: the one Moodle lists, unless that is
 * none or its `document/unknown`, which Moodle serves as `application/octet-stream` and so checks
 * nothing.
 */
fun org.ntust.app.tigerduck.network.model.moodle.MoodleFile.checkableMimeType(): String? =
    mimeType?.trim()?.takeUnless {
        it.isEmpty() || it.substringBefore(';').trim().equals("document/unknown", ignoreCase = true)
    }

/** Whether the file's own name says it is a web page. */
fun org.ntust.app.tigerduck.network.model.moodle.MoodleFile.isWebPage(): Boolean =
    name.substringAfterLast('.', "").lowercase() in WEB_PAGE_EXTENSIONS

private val WEB_PAGE_EXTENSIONS = setOf("html", "htm", "shtml", "xhtml", "mht", "mhtml")

/**
 * The extensions of a web page or an SVG, which a browser renders and may run scripts in: such a
 * file never opens in a viewer but goes to the share sheet, as iOS keeps it out of Quick Look.
 */
val SHARED_NOT_VIEWED_EXTENSIONS = WEB_PAGE_EXTENSIONS + "svg"
