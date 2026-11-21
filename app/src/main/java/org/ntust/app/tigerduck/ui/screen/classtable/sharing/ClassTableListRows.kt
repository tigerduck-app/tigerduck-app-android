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

    /** Where the person at [index] has gone: its section's placement, and the person after it there. */
    fun target(rows: List<Row>, index: Int): Pair<Placement, String?> {
        val placement = rows.take(index).filterIsInstance<Header>().last().placement
        val next = rows.drop(index + 1).takeWhile { it !is Header }.filterIsInstance<Person>().firstOrNull()
        return placement to next?.person?.id
    }
}
