package org.ntust.app.tigerduck.shared.tls

import java.util.Base64

/**
 * The Ed25519 public keys a pin document may be signed with (TLS pins spec, A3), by `key_id`,
 * each the standard base64 of the raw 32-byte key: TigerSync's current key and one standby key
 * whose private half is kept offline. A document signed by any other key is refused. To rotate,
 * ship apps that know the new key first, then switch the server. Never put a test key here:
 * tests pass their own keys to [PinDocumentVerifier].
 */
object PinDocumentKeys {

    private val published: Map<String, String> = mapOf(
        // The server's current key.
        "td-pins-2026a" to "aTiq15fBtq+AuQt/+/DmJbRP7p0yge4psbaqrxf/sYc=",
        // The offline standby.
        "td-pins-2026b" to "bzAyG2WrY+A1T/E2D4IGH6YH+r7zOtC5cnuvd3j3WSo=",
    )

    val keys: Map<String, ByteArray> = published.mapValues { (_, key) -> Base64.getDecoder().decode(key) }
}
