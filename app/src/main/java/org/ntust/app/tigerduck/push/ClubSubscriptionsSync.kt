// The `club_subscriptions` settings document (`GET/PUT …/settings/club_subscriptions`), the
// way the `notification` document syncs: through SettingsDocumentApiClient, with the sign-in
// each request carries, compare-and-swap on the revision read, and a 409 merged with the copy it
// carries. Unlike the notification document there is no three-way merge: every value carries its
// own stamp, so a push and a pull are one step, as on iOS (ClubSubscriptionsSync.swift, the Apple
// repo's docs/decisions/0030).

package org.ntust.app.tigerduck.push

import com.google.gson.JsonObject
import org.ntust.app.tigerduck.clubs.ClubSubscriptionsDocument
import org.ntust.app.tigerduck.clubs.ClubSubscriptionsWire

/** The settings-document namespace of the club subscriptions. */
internal const val CLUB_SUBSCRIPTIONS_NAMESPACE = "club_subscriptions"

/**
 * Merges [local] with the server's copy value by value ([ClubSubscriptionsDocument.merged]) and
 * writes the result when it differs from what the server holds, with the server's revision; a 409
 * is merged once more with the newer copy it carries. The write splices the merged values over the
 * document read ([merging]), so keys this build does not know survive. Returns the document the
 * server holds once the merge is on it, or null when [mayWrite] turned false before a write or the
 * write was abandoned.
 */
internal suspend fun syncClubSubscriptions(
    local: ClubSubscriptionsDocument,
    transport: SettingsDocumentTransport,
    mayWrite: () -> Boolean,
): ClubSubscriptionsDocument? {
    var current = transport.read()
    repeat(2) {
        val server = current?.let { ClubSubscriptionsWire.read(it.document) } ?: ClubSubscriptionsDocument()
        val merged = ClubSubscriptionsDocument.merged(local = local, server = server)
        if (merged == server) return merged
        if (!mayWrite()) return null
        val written = merging(ClubSubscriptionsWire.json(merged), into = current?.document ?: JsonObject())
        when (val answer = transport.write(written, current?.revision, mayWrite)) {
            is SettingsWriteResult.Written -> return merged
            is SettingsWriteResult.Conflict -> current = answer.server
            SettingsWriteResult.Abandoned -> return null
        }
    }
    // Two 409s in a row: the run is owed, and the next one tries again.
    throw SettingsDocumentApiException("write $CLUB_SUBSCRIPTIONS_NAMESPACE: conflict not resolved")
}
