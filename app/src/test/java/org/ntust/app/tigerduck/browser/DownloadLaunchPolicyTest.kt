package org.ntust.app.tigerduck.browser

import android.app.Application
import android.net.Uri
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.ntust.app.tigerduck.shared.tls.pinnedClientBuilder
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DownloadLaunchPolicyTest {
    private val sent = mutableListOf<String>()
    private val saved = mutableListOf<String>()

    private fun transfer(next: String) = MediaStoreDownloadTransfer(
        context = RuntimeEnvironment.getApplication(),
        allowlist = { BrowserAllowlist.DEFAULT },
        cookieFor = { null },
        keepCookie = { _, _ -> },
        userAgent = { null },
        client = pinnedClientBuilder(null).addInterceptor { chain ->
            val request = chain.request()
            sent += request.url.toString()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (sent.size == 1) 302 else 200).message("fake")
                .apply { if (sent.size == 1) header("Location", next) }
                .body("PDF".toResponseBody("application/pdf".toMediaType())).build()
        }.build(),
        save = { _, _, input, _ ->
            saved += input.readBytes().decodeToString()
            Uri.parse("content://downloads/9")
        },
    )

    private fun plan(policy: BrowserAllowlist): DownloadPlan.Save = BrowserDownloadPlans.plan(
        "https://first.example/a.pdf", null, "application/pdf", policy, { _, _, _ -> "a.pdf" }, { null },
    ) as DownloadPlan.Save

    @Test
    fun `a restricted download keeps the first host added at launch`() = runTest {
        val policy = BrowserLaunchPolicy.allowlist("https://first.example/", BrowserAllowlist(emptyList()))
        transfer("https://first.example/b.pdf").download(plan(policy)) { _, _ -> }
        assertEquals(listOf("https://first.example/a.pdf", "https://first.example/b.pdf"), sent)
        assertEquals(listOf("PDF"), saved)
    }

    @Test
    fun `a launch restriction refuses a redirect even when the stored defaults allow it`() = runTest {
        val policy = BrowserLaunchPolicy.allowlist("https://first.example/", BrowserAllowlist(emptyList()))
        try {
            transfer("https://other.ntust.edu.tw/b.pdf").download(plan(policy)) { _, _ -> }
            fail("left this browser's allowlist")
        } catch (_: IOException) {
        }
        assertEquals(listOf("https://first.example/a.pdf"), sent)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `an unrestricted launch follows a download outside the stored defaults`() = runTest {
        transfer("https://outside.example/b.pdf").download(plan(BrowserLaunchPolicy.allowlist("https://first.example/"))) { _, _ -> }
        assertEquals(listOf("https://first.example/a.pdf", "https://outside.example/b.pdf"), sent)
        assertEquals(listOf("PDF"), saved)
    }
}
