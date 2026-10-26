package org.ntust.app.tigerduck.ui.component

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.network.tls.PinTestChain
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest
import java.util.Locale

/** The pin-mismatch prompt (TLS pins spec, A6), with literal texts. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class PinMismatchSheetTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val chain = PinTestChain()

    private val texts = PinMismatchTexts(
        title = "Certificate changed",
        message = "The website's certificate doesn't match the one on record.",
        issuer = "Issued by ${issuerOrganisation(chain.leaf.certificate)}",
        showDetails = "Details",
        details = "Explanation for moodle2.ntust.edu.tw",
        viewCertificate = "View certificate",
        certificateListTitle = "Certificates",
        intermediate = "Intermediate",
        root = "Root",
        name = "Name",
        issuerLabel = "Issuer",
        validity = "Valid",
        fingerprint = "SHA-256 fingerprint",
        cancel = "Cancel",
        continueLabel = "Continue",
        back = "Back",
    )

    private val answers = mutableListOf<String>()

    private fun show() = compose.setContent {
        MaterialTheme {
            PinMismatchSheet(
                host = chain.host,
                texts = texts,
                certificates = certificateRows(chain.mismatch(), texts, Locale.US),
                onCancel = { answers += "cancel" },
                onContinue = { answers += "continue" },
            )
        }
    }

    @Test
    fun `the prompt names the host and the leaf's issuer`() {
        show()

        compose.onNodeWithText("Certificate changed").assertIsDisplayed()
        compose.onNodeWithText(chain.host).assertIsDisplayed()
        compose.onNodeWithText("Issued by Issuing CA").assertIsDisplayed()
    }

    @Test
    fun `Continue and Cancel answer the prompt`() {
        show()

        compose.onNodeWithText("Continue").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()

        assertEquals(listOf("continue", "cancel"), answers)
    }

    @Test
    fun `the details hold the explanation and the certificate list`() {
        show()

        compose.onNodeWithText("Details").performScrollTo().performClick()
        compose.onNodeWithText("Explanation for moodle2.ntust.edu.tw").assertIsDisplayed()
        compose.onNodeWithText("View certificate").performScrollTo().performClick()

        compose.onNodeWithText("Certificates").assertIsDisplayed()
        compose.onNodeWithText("Intermediate").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Root").performScrollTo().assertIsDisplayed()
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(chain.leaf.certificate.encoded).joinToString(":") { "%02X".format(it) }
        compose.onNodeWithText(fingerprint).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the leaf's section is headed by its host`() {
        val rows = certificateRows(chain.mismatch(), texts, Locale.US)

        assertEquals(listOf(chain.host, "Intermediate", "Root"), rows.map { it.heading })
        assertEquals(listOf(chain.host, "Issuing CA", "Public Root"), rows.map { it.name })
        assertEquals(listOf("Issuing CA", "Public Root", "Public Root"), rows.map { it.issuer })
    }
}
