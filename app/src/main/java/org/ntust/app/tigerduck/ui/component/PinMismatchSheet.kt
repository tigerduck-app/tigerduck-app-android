package org.ntust.app.tigerduck.ui.component

import android.icu.text.ListFormatter
import android.net.http.SslCertificate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.network.tls.PinMismatchPrompts
import org.ntust.app.tigerduck.shared.tls.PinMismatch
import org.ntust.app.tigerduck.util.replaceIosArg
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.text.DateFormat
import java.util.Locale

/** The words the pin-mismatch sheet shows, resolved by its caller (TLS pins spec, A6). */
data class PinMismatchTexts(
    val title: String,
    val message: String,
    /** "Issued by …" for the leaf's issuing organisation (D19). */
    val issuer: String,
    val showDetails: String,
    val details: String,
    val viewCertificate: String,
    val certificateListTitle: String,
    val intermediate: String,
    val root: String,
    val name: String,
    val issuerLabel: String,
    val validity: String,
    val fingerprint: String,
    val cancel: String,
    val continueLabel: String,
    val back: String,
)

/** One certificate of the validated chain, as the certificate list shows it. */
data class PinCertificateRow(
    val heading: String,
    val name: String,
    val issuer: String,
    val validity: String,
    /** SHA-256 of the whole certificate, as colon-separated hex. */
    val fingerprint: String,
)

/** The app-root host: the queued prompt, when there is one, with its strings. */
@Composable
fun PinMismatchHost(prompts: PinMismatchPrompts) {
    val mismatch by prompts.pending.collectAsState()
    // Checked again on its way in, and each time the app comes back: one queued or left up in
    // the background may have lapsed meanwhile.
    LifecycleStartEffect(mismatch) {
        prompts.revalidate()
        onStopOrDispose {}
    }
    val shown = mismatch ?: return
    val locale = LocalConfiguration.current.locales[0]
    val domains = ListFormatter.getInstance(locale).format(shown.domains.map { it.name })
    val texts = PinMismatchTexts(
        title = stringResource(R.string.tls_pin_mismatch_title),
        message = stringResource(R.string.tls_pin_mismatch_message),
        issuer = stringResource(R.string.tls_pin_mismatch_issuer).replaceIosArg(issuerOrganisation(shown.chain.first())),
        showDetails = stringResource(R.string.tls_pin_mismatch_show_details),
        details = stringResource(R.string.tls_pin_mismatch_details).replaceIosArg(1, shown.host).replaceIosArg(2, domains),
        viewCertificate = stringResource(R.string.tls_pin_mismatch_view_certificate),
        certificateListTitle = stringResource(R.string.tls_certificate_list_title),
        intermediate = stringResource(R.string.tls_certificate_intermediate),
        root = stringResource(R.string.tls_certificate_root),
        name = stringResource(R.string.tls_certificate_name),
        issuerLabel = stringResource(R.string.tls_certificate_issuer),
        validity = stringResource(R.string.tls_certificate_validity),
        fingerprint = stringResource(R.string.tls_certificate_fingerprint),
        cancel = stringResource(R.string.action_cancel),
        continueLabel = stringResource(R.string.action_continue),
        back = stringResource(R.string.action_back),
    )
    val rows = remember(shown) { certificateRows(shown, texts, locale) }
    PinMismatchSheet(
        host = shown.host,
        texts = texts,
        certificates = rows,
        onCancel = prompts::cancel,
        onContinue = prompts::continueToSite,
    )
}

/**
 * The prompt (A6): a lock-and-warning icon, the title and the message; the host and the leaf's
 * issuer; a details disclosure with the explanation and View certificate, which shows one section
 * per certificate of the validated chain; Cancel and Continue side by side, Continue in the
 * accent colour. Dismissing the sheet is a Cancel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinMismatchSheet(
    host: String,
    texts: PinMismatchTexts,
    certificates: List<PinCertificateRow>,
    onCancel: () -> Unit,
    onContinue: () -> Unit,
) {
    var detailsOpen by rememberSaveable(host) { mutableStateOf(false) }
    var certificatesOpen by rememberSaveable(host) { mutableStateOf(false) }
    DetentSheet(onDismissed = onCancel) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            if (certificatesOpen) {
                CertificateList(texts, certificates, onBack = { certificatesOpen = false })
            } else {
                Icon(
                    Icons.Filled.GppMaybe,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(40.dp).align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    texts.title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.align(Alignment.CenterHorizontally).semantics { heading() },
                )
                Spacer(Modifier.height(8.dp))
                Text(texts.message, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                Text(host, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(texts.issuer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { detailsOpen = !detailsOpen }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(texts.showDetails, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Icon(if (detailsOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                }
                AnimatedVisibility(detailsOpen) {
                    Column {
                        Text(texts.details, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { certificatesOpen = true }) { Text(texts.viewCertificate) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(texts.cancel) }
                    Button(onClick = onContinue, modifier = Modifier.weight(1f)) { Text(texts.continueLabel) }
                }
            }
        }
    }
}

@Composable
private fun CertificateList(texts: PinMismatchTexts, certificates: List<PinCertificateRow>, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = texts.back)
        }
        Text(texts.certificateListTitle, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    }
    certificates.forEachIndexed { index, row ->
        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text(row.heading, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(6.dp))
        CertificateField(texts.name, row.name)
        CertificateField(texts.issuerLabel, row.issuer)
        CertificateField(texts.validity, row.validity)
        CertificateField(texts.fingerprint, row.fingerprint, monospace = true)
    }
}

@Composable
private fun CertificateField(label: String, value: String, monospace: Boolean = false) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (monospace) FontFamily.Monospace else null,
            )
        }
    }
}

/** The organisation that issued [certificate], or its common name when it names none (D19). */
internal fun issuerOrganisation(certificate: X509Certificate): String {
    val issuer = SslCertificate(certificate).issuedBy
    return issuer.oName.takeIf { it.isNotBlank() } ?: issuer.cName.takeIf { it.isNotBlank() } ?: certificate.issuerX500Principal.name
}

/** The validated chain's sections: the leaf headed by its host, then the intermediates, then the root. */
internal fun certificateRows(mismatch: PinMismatch, texts: PinMismatchTexts, locale: Locale): List<PinCertificateRow> {
    val dates = DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
    val last = mismatch.chain.lastIndex
    return mismatch.chain.mapIndexed { index, certificate ->
        val ssl = SslCertificate(certificate)
        PinCertificateRow(
            heading = when {
                index == 0 -> mismatch.host
                index == last -> texts.root
                else -> texts.intermediate
            },
            name = ssl.issuedTo.cName.takeIf { it.isNotBlank() } ?: certificate.subjectX500Principal.name,
            issuer = ssl.issuedBy.cName.takeIf { it.isNotBlank() } ?: certificate.issuerX500Principal.name,
            validity = "${dates.format(certificate.notBefore)} – ${dates.format(certificate.notAfter)}",
            fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                .joinToString(":") { "%02X".format(it) },
        )
    }
}
