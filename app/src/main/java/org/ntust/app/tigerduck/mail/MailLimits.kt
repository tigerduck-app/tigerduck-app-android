package org.ntust.app.tigerduck.mail

/**
 * Ceilings on how much of one message the app will hold in memory, or write to disk, at once.
 *
 * Every size behind these is chosen by whoever sent the mail, not by the student reading it, and
 * an `OutOfMemoryError` is an `Error`: neither `AngusMailSession.io` nor the view models'
 * `catch (e: Exception)` would stop one, so it reaches the uncaught handler and kills the
 * process. A bounded read that shows slightly less of a hostile mail is the better outcome in
 * every case here.
 */
object MailLimits {
    /** Same cap as iOS: a larger inline image is not inlined (it stays an attachment, and in the source view). */
    const val INLINE_IMAGE_BYTES = 5L * 1024 * 1024

    /**
     * The largest mail whose raw source the app will download and decode.
     *
     * The source is buffered whole and then decoded into a `String` (UTF-16, so roughly twice
     * the bytes again). At the server's own 50 MB SMTP SIZE limit
     * (`ComposeRules.MAX_ENCODED_BYTES`) that is ~150 MB resident before `MailCache` gets to
     * refuse to store it — and the cache's own per-entry ceiling is 10 MB, so anything above
     * this was never going to be kept anyway.
     */
    const val SOURCE_BYTES = 10L * 1024 * 1024

    /**
     * The most of a single `text/plain` or `text/html` part that is read into memory. Past this
     * the part is truncated rather than refused: the mail still opens and still shows its first
     * few megabytes, which is far more text than anyone reads on a phone.
     */
    const val TEXT_PART_BYTES = 4 * 1024 * 1024

    /**
     * The most of one attachment the app will write out — into the cache slot an Open serves from,
     * or into the document a Save was pointed at.
     *
     * Unlike the limits above this is not about memory (an attachment streams straight to disk),
     * but the bytes still arrive entirely under the sender's control, and the size shown beside
     * the attachment is only what `BODYSTRUCTURE` claimed: it need not be true, and need not be
     * there at all. So this is enforced *while copying* rather than from the declared size, and a
     * part that runs past it fails with `MailError.TooLarge` instead of being truncated — a
     * half-written file sitting where the user asked for one looks exactly like a saved file.
     *
     * Set at the server's own SMTP SIZE limit (`ComposeRules.MAX_ENCODED_BYTES`, 50 MiB). A part's
     * decoded content cannot legitimately outgrow the encoded size of the whole message carrying
     * it, so nothing Mail2000 would accept for delivery is refused by this.
     */
    const val ATTACHMENT_BYTES = 50L * 1024 * 1024
}
