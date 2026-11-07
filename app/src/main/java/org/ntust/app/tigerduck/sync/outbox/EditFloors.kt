package org.ntust.app.tigerduck.sync.outbox

import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.EditTime

/**
 * Where the end of a write raises the floor of the edits of its key still queued: the per-key rule
 * of `age_ms`, as the iPhone's (tigerduck-app#1008). The client calls it as each write of [key]
 * that went out at `start` is answered at `end`, under its key's lock
 * ([org.ntust.app.tigerduck.push.FieldAges]), and the outbox ([Outbox]) asks each channel.
 */
fun interface EditFloors {
    /**
     * A write of [key] went out at [start], its age read then, and its answer arrived at [end],
     * under [signIn]: every queued edit of [key] made at or after [start] counts its age from [end]
     * at least, on disk when this returns. Nothing once [signIn] has ended.
     */
    suspend fun raise(key: String, start: EditTime, end: EditTime, signIn: SignIn)
}
