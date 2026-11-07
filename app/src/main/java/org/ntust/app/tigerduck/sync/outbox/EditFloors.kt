package org.ntust.app.tigerduck.sync.outbox

import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.EditTime

/**
 * Where the end of a write raises the floor of the edits of its key still queued, and where a write
 * reads a queued edit's floor: the per-key rule of `age_ms`, as the iPhone's (tigerduck-app#1008).
 * The client calls both under its key's lock ([org.ntust.app.tigerduck.push.FieldAges]), and the
 * outbox ([Outbox]) asks each channel.
 */
interface EditFloors {
    /**
     * A write of [key] went out at [start], its age read then, and its answer arrived at [end],
     * under [signIn]: every queued edit of [key] made at or after [start] counts its age from [end]
     * at least, on disk when this returns. Nothing once [signIn] has ended.
     */
    suspend fun raise(key: String, start: EditTime, end: EditTime, signIn: SignIn)

    /**
     * The floor a queued edit of [key] made at [madeAt] keeps now, as its queue holds it on disk:
     * read as its write goes, so it is never older than the copy the write was handed. Null for
     * none, or once [signIn] has ended.
     */
    suspend fun floorOf(key: String, madeAt: EditTime, signIn: SignIn): EditTime?
}

/** The latest of [this] times, null for none. */
fun Iterable<EditTime>.latest(): EditTime? = reduceOrNull { a, b -> if (a.isBefore(b)) b else a }
