package org.ntust.app.tigerduck.browser

import android.net.http.SslCertificate
import java.security.MessageDigest
import java.util.Date

/** The page certificate's details the browser's Certificate page shows. */
data class CertificateInfo(
    val issuedToName: String?,
    val issuedToOrganization: String?,
    val issuedByName: String?,
    val issuedByOrganization: String?,
    val validFrom: Date?,
    val validUntil: Date?,
    /** SHA-256 over the certificate, as `AB:CD:…`; null when the WebView gives no certificate to hash. */
    val sha256: String?,
) {
    companion object {
        fun of(certificate: SslCertificate): CertificateInfo = CertificateInfo(
            issuedToName = certificate.issuedTo?.cName?.takeIf { it.isNotBlank() },
            issuedToOrganization = certificate.issuedTo?.oName?.takeIf { it.isNotBlank() },
            issuedByName = certificate.issuedBy?.cName?.takeIf { it.isNotBlank() },
            issuedByOrganization = certificate.issuedBy?.oName?.takeIf { it.isNotBlank() },
            validFrom = certificate.validNotBeforeDate,
            validUntil = certificate.validNotAfterDate,
            sha256 = certificate.x509Certificate?.encoded?.let(::fingerprint),
        )

        /** [encoded]'s SHA-256 as colon-separated upper-case hex pairs. */
        fun fingerprint(encoded: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(encoded).joinToString(":") { "%02X".format(it) }
    }
}
