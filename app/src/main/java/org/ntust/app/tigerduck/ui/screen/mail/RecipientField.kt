package org.ntust.app.tigerduck.ui.screen.mail

import org.ntust.app.tigerduck.mail.compose.ComposeRules
import org.ntust.app.tigerduck.mail.mime.AddressParser

/**
 * One To, Cc or Bcc field of the compose form, as its bubbles show it: the recipients already
 * finished, in order, and what is still being typed among them.
 *
 * Sending reads [entries], each recipient on its own ([ComposeRules.parseRecipients]), never split
 * again from a joined string -- so a bubble is refused exactly when it is drawn red, even one with
 * a quote or `<` left open, which inside a joined string would swallow every recipient after it.
 */
data class RecipientField(
    val tokens: List<Token> = emptyList(),
    /**
     * What is still being typed. Written only by the field on screen, which keeps the live copy
     * along with its cursor and the keyboard's composition and hands every change on (see
     * [MailRecipientTokenField]); nothing else may set it, or the two would disagree.
     */
    val draft: String = "",
    /** Where [draft] stands among [tokens]: a bubble taken back to be edited keeps its place. */
    val draftAt: Int = tokens.size,
    /** The [Token.id] the next bubble gets. */
    val nextId: Long = tokens.size.toLong(),
) {
    /**
     * One bubble. A tap names it by [id], not by its place, which moves as others are removed; ids
     * are never reused, so a tap meant for a bubble already gone finds nothing rather than the
     * one that took its place.
     */
    data class Token(val id: Long, val text: String)

    /** Every recipient in order, the one being typed included where it stands. */
    val entries: List<String>
        get() = tokens.map { it.text }.toMutableList()
            .apply { add(draftAt.coerceIn(0, size), draft.trim()) }
            .filter { it.isNotEmpty() }

    /** [entries] on one line, as a draft or a reply's prefill writes recipients. */
    val text: String get() = entries.joinToString(", ")

    /** [finished] become bubbles where the typing stands, and [remainder] is what is still being typed. */
    fun typed(finished: List<String>, remainder: String): RecipientField {
        val added = finished.map { it.trim() }.filter { it.isNotEmpty() }
        val at = draftAt.coerceIn(0, tokens.size)
        return copy(
            tokens = tokens.subList(0, at) + added.mapIndexed { i, text -> Token(nextId + i, text) } + tokens.subList(at, tokens.size),
            draft = remainder,
            draftAt = at + added.size,
            nextId = nextId + added.size,
        )
    }

    /** What was being typed becomes a bubble where it stands, and typing moves back to the end. */
    fun finishDraft(): RecipientField = typed(listOf(draft), "").let { it.copy(draftAt = it.tokens.size) }

    /**
     * The bubble [id] is taken back into the text to be edited, in its own place, so that only
     * looking at it changes nothing -- what was being typed is finished first. Unchanged when the
     * bubble is already gone.
     */
    fun edit(id: Long): RecipientField {
        if (tokens.none { it.id == id }) return this
        val finished = typed(listOf(draft), "")
        val index = finished.tokens.indexOfFirst { it.id == id }
        return finished.copy(
            tokens = finished.tokens.filterIndexed { i, _ -> i != index },
            draft = finished.tokens[index].text,
            draftAt = index,
        )
    }

    /** Backspace with nothing typed: the bubble just before the typing is taken back to be edited. */
    fun editPrevious(): RecipientField =
        if (draft.isNotEmpty() || draftAt !in 1..tokens.size) this else edit(tokens[draftAt - 1].id)

    fun remove(id: Long): RecipientField {
        val index = tokens.indexOfFirst { it.id == id }
        if (index < 0) return this
        return copy(
            tokens = tokens.filterIndexed { i, _ -> i != index },
            draftAt = if (index < draftAt) draftAt - 1 else draftAt,
        )
    }

    companion object {
        /**
         * A value set from outside the field -- a reply's prefilled recipients, a draft reopened
         * -- as bubbles. Only `,`/`;` split here, exactly as sending splits it: the value is
         * already a list, and a space inside it belongs to a name.
         */
        fun of(value: String): RecipientField = RecipientField().typed(AddressParser.splitTopLevel(value), "")
    }
}

/**
 * The rule the recipient fields turn typing into bubbles by. Pure, so it can be tested without a
 * screen.
 */
internal object MailRecipientTokens {
    data class Consumed(val finished: List<String>, val remainder: String)

    /**
     * Splits what has been typed into the recipients it finishes and what is still being typed.
     *
     * `,` and `;` always end a recipient. A space ends one only once what came before it holds an
     * `@` -- so `王大明 <wang@mail.ntust.edu.tw>` can still be typed name first -- and never inside
     * quotes or angle brackets, where it is part of the name or the address. The splitting is
     * [AddressParser.splitTopLevel]'s own, so a bubble never cuts what sending would read as one
     * recipient.
     */
    fun consume(typed: String): Consumed {
        val parts = AddressParser.splitTopLevel(typed, spaceEndsAddress = true)
        // Leading whitespace is never the start of an address.
        return Consumed(parts.dropLast(1).map { it.trim() }.filter { it.isNotEmpty() }, parts.last().trimStart())
    }
}
