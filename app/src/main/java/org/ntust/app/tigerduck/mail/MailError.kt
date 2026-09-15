package org.ntust.app.tigerduck.mail

import jakarta.mail.AuthenticationFailedException
import jakarta.mail.FolderClosedException
import jakarta.mail.StoreClosedException
import jakarta.mail.search.SearchException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** What went wrong, in the terms the UI and the checker act on (spec §7.1, §12.3). */
sealed class MailError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class AuthFailed(cause: Throwable? = null) : MailError("authentication failed", cause)
    class Network(cause: Throwable? = null) : MailError("network error", cause)
    /** Includes a pin mismatch from network_security_config.xml. Never offer to continue. */
    class Certificate(cause: Throwable? = null) : MailError("certificate check failed", cause)
    class ServerBusy(cause: Throwable? = null) : MailError("server busy", cause)
    class SearchUnsupported(cause: Throwable? = null) : MailError("search unsupported", cause)
    class Protocol(message: String, cause: Throwable? = null) : MailError(message, cause)
    /** Thrown if anything tries to open a socket while the demo mailbox is active. */
    class DemoMode : MailError("demo mode never opens sockets")
    /** A folder's UIDVALIDITY no longer matches the caller's cached page; nothing on the server was touched. */
    class FolderChanged : MailError("folder changed; refresh")
}

object MailErrors {
    fun classify(t: Throwable): MailError {
        if (t is MailError) return t
        val seen = HashSet<Throwable>()
        var current: Throwable? = t
        while (current != null && seen.add(current)) {
            when (current) {
                is AuthenticationFailedException -> return MailError.AuthFailed(t)
                is SSLPeerUnverifiedException, is SSLHandshakeException,
                is CertificateException, is CertPathValidatorException -> return MailError.Certificate(t)
                is SearchException -> return MailError.SearchUnsupported(t)
                is UnknownHostException, is ConnectException, is SocketTimeoutException,
                is NoRouteToHostException, is SocketException,
                is FolderClosedException, is StoreClosedException -> return MailError.Network(t)
            }
            current = current.cause
        }
        val text = generateSequence(t) { it.cause }.take(8).mapNotNull { it.message }.joinToString(" ").lowercase()
        return when {
            "too many" in text || "busy" in text || "try again" in text || "maximum" in text -> MailError.ServerBusy(t)
            t is IOException -> MailError.Network(t)
            else -> MailError.Protocol(t.message ?: t.javaClass.simpleName, t)
        }
    }
}
