package org.ntust.app.tigerduck.library

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.shared.LibraryServiceError
import java.io.IOException
import java.net.SocketTimeoutException

class LibraryErrorMessageTest {

    private fun message(error: Throwable) =
        libraryErrorMessage(error, R.string.library_qr_generate_failed) { id -> "string $id" }

    @Test
    fun `no connection is said in the app's own words`() {
        assertEquals("string ${R.string.error_network_unavailable}", message(SocketTimeoutException("timeout")))
        assertEquals("string ${R.string.error_network_unavailable}", message(IOException("Canceled")))
    }

    @Test
    fun `a library that did not answer is said in the app's own words`() {
        assertEquals(
            "string ${R.string.error_invalid_server_response}",
            message(LibraryServiceError.LoginUnavailable("Library request failed (HTTP 503)")),
        )
    }

    @Test
    fun `a refusal shows the library's own message`() {
        assertEquals("wrong password", message(LibraryServiceError.LoginFailed("wrong password")))
    }

    @Test
    fun `a missing account and a silent failure have words of their own`() {
        assertEquals("string ${R.string.error_library_credentials_not_found}", message(LibraryServiceError.CredentialsNotFound()))
        assertEquals("string ${R.string.library_qr_generate_failed}", message(IllegalStateException()))
    }
}
