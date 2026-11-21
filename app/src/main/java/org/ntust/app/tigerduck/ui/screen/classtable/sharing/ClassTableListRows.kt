package org.ntust.app.tigerduck.ui.screen.classtable.sharing

import org.ntust.app.tigerduck.data.sharing.ImportedPerson
import org.ntust.app.tigerduck.data.sharing.ImportedPerson.Placement

/**
 * The Class tables list's rows, top to bottom: each placement's header with its people under it,
 * the user's own class table first under the rotation's. A drag moves a person one row at a time,
 * across headers too, so a person can go into any section, an empty one included, but never above
 * the user. Ported from iOS `ClassTableListRows`.
 */
internal object ClassTableListRows {
    sealed interface Row {
        val key: String
    }

    data class Header(val placement: Placement) : Row {
        override val key: String get() = "header-${placement.stored}"
    }

    data object User : Row {
        override val key: String get() = "user"
    }

    data class Person(val person: ImportedPerson) : Row {
        override val key: String get() = person.id
    }

    fun of(people: List<ImportedPerson>): List<Row> = Placement.entries.flatMap { placement ->
        listOf(Header(placement)) +
            (if (placement == Placement.ROTATION) listOf(User) else emptyList()) +
            people.filter { it.placement == placement }.map(::Person)
    }

    /**
     * [rows] with the person at [index] moved one row down, or up, past the row there: into the
     * next section's top under its header, or the last section's bottom above it. Null when it
     * cannot go: past either end, above the user, or for a row that is not a person.
     */
    fun step(rows: List<Row>, index: Int, down: Boolean): List<Row>? {
        val to = if (down) index + 1 else index - 1
        if (rows.getOrNull(index) !is Person || to !in rows.indices) return null
        if (rows[to] == User || to == 0) return null
        return rows.toMutableList().apply { add(to, removeAt(index)) }
    }

    /**
     * [rows] after the person [key] was dragged [offset] from its place, with what is left of the
     * offset: it passes each row it has gone more than half of, [heightOf] giving that row's, in
     * the offset's direction only. A row passed at exactly half would leave the other half, which
     * passed back the other way would swap the two rows for ever.
     */
    fun dragged(rows: List<Row>, key: String, offset: Float, heightOf: (Row) -> Float?): Pair<List<Row>, Float> {
        var moved = rows
        var left = offset
        val down = offset > 0
        while (left != 0f && (left > 0) == down) {
            val index = moved.indexOfFirst { it.key == key }.takeIf { it >= 0 } ?: break
            val past = moved.getOrNull(if (down) index + 1 else index - 1) ?: break
            val height = heightOf(past) ?: break
            if (kotlin.math.abs(left) <= height / 2) break
            moved = step(moved, index, down) ?: break
            left += if (down) -height else height
        }
        return moved to left
    }

    /** Where the person at [index] has gone: its section's placement, and the person after it there. */
    fun target(rows: List<Row>, index: Int): Pair<Placement, String?> {
        val placement = rows.take(index).filterIsInstance<Header>().last().placement
        val next = rows.drop(index + 1).takeWhile { it !is Header }.filterIsInstance<Person>().firstOrNull()
        return placement to next?.person?.id
    }
}
