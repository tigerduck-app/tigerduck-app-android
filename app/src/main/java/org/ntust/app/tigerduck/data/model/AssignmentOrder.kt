package org.ntust.app.tigerduck.data.model

import java.text.Collator
import java.util.Date
import java.util.Locale

/**
 * The order of every assignment list, as iOS's `Array+Assignments.swift` (tigerduck-app#1029): the
 * ones with a due date by it, ties in the order given, then the ones Moodle gives none, by title.
 */
object AssignmentOrder {

    /** [items] with a due date by [dates], then those without by title. */
    fun <T> sorted(
        items: List<T>,
        dueDate: (T) -> Date?,
        title: (T) -> String,
        dates: Comparator<Date> = naturalOrder(),
    ): List<T> {
        val dated = items.mapNotNull { item -> dueDate(item)?.let { item to it } }
            .sortedWith { a, b -> dates.compare(a.second, b.second) }
            .map { it.first }
        return dated + items.filter { dueDate(it) == null }.sortedWith(compareBy(titles(), title))
    }

    /** Those still to come, the soonest first, then the past ones, the latest first, then the undated. */
    fun <T> partitioned(items: List<T>, dueDate: (T) -> Date?, title: (T) -> String, now: Date): List<T> =
        sorted(items, dueDate, title) { a, b ->
            val aToCome = !a.before(now)
            when {
                aToCome != !b.before(now) -> if (aToCome) -1 else 1
                aToCome -> a.compareTo(b)
                else -> b.compareTo(a)
            }
        }

    /**
     * Titles as iOS's `localizedStandardCompare` orders them: by the language's collation, case
     * aside, and a run of digits by its value, so "HW 9" comes before "HW 10".
     */
    fun titles(locale: Locale = Locale.getDefault()): Comparator<String> {
        val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
        return Comparator { a, b -> collator.compare(numbered(a), numbered(b)) }
    }

    private val DIGITS = Regex("""\d+""")

    // Each run of digits padded to one width, which the collation then orders by value.
    private fun numbered(title: String) = DIGITS.replace(title) { it.value.padStart(NUMBER_WIDTH, '0') }

    private const val NUMBER_WIDTH = 20
}

/** By the student's own due date, an extension included, the undated last by title. */
fun List<Assignment>.sortedByDueDate(): List<Assignment> =
    AssignmentOrder.sorted(this, Assignment::studentDueDate, Assignment::title)

/** Those still to come by the student's own due date, then the past ones, then the undated. */
fun List<Assignment>.partitionedByDueDate(now: Date): List<Assignment> =
    AssignmentOrder.partitioned(this, Assignment::studentDueDate, Assignment::title, now)
