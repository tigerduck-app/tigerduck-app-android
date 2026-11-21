package org.ntust.app.tigerduck.data.sharing

import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.ImportedPersonDto
import org.ntust.app.tigerduck.data.model.ImportedTermDto
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.time.AcademicTerm
import java.time.Instant

/** Someone whose class table the user imported from a share code. */
data class ImportedPerson(
    val id: String,
    /** Null when the share code carried none. */
    val studentId: String?,
    val nickname: String,
    val importedAt: Instant,
    val sharedAt: Instant?,
    val terms: List<Term>,
    /** Where the class table switcher lists them; a new import goes in the rotation. */
    val placement: Placement = Placement.ROTATION,
) {
    data class Term(
        /** The app's semester code, such as `1151`. */
        val semester: String,
        val courseNos: List<String>,
        /** The courses' names, times and rooms from NTUST's course query. */
        val courses: List<Course>,
    )

    /**
     * Where the class table switcher lists someone: in the double tap's rotation, which the menus
     * list under the user, in the long-press menu on their own, or in the menus' Other submenu.
     * [stored] is the file's spelling, iOS `ImportedPerson.Placement`'s raw value.
     */
    enum class Placement(val stored: String) {
        ROTATION("rotation"),
        LONG_PRESS_MENU("longPressMenu"),
        OTHER("other");

        companion object {
            /** A file from before placements has none, and a later build's this one does not know reads as the rotation too. */
            fun stored(value: String?): Placement = entries.firstOrNull { it.stored == value } ?: ROTATION
        }
    }

    /** Newest first, as the term picker lists them. */
    val semesters: List<String> get() = terms.map { it.semester }.sortedWith(AcademicTerm.newestFirst)

    /** The term to open this class table on: [keeping] when the person shared it, else their latest. */
    fun semester(keeping: String): String =
        if (keeping in semesters) keeping else semesters.firstOrNull() ?: keeping

    fun courses(semester: String): List<Course> = terms.firstOrNull { it.semester == semester }?.courses.orEmpty()
}

internal fun ImportedPerson.toDto() = ImportedPersonDto(
    id = id,
    studentId = studentId,
    nickname = nickname,
    importedAtMillis = importedAt.toEpochMilli(),
    sharedAtMillis = sharedAt?.toEpochMilli(),
    terms = terms.map { ImportedTermDto(it.semester, it.courseNos, it.courses) },
    placement = placement.stored,
)

/** Null when a key the store always writes is missing; a missing placement is the rotation. */
internal fun ImportedPersonDto.toPerson(): ImportedPerson? {
    val id = id ?: return null
    val nickname = nickname ?: return null
    val terms = terms?.map { it?.toTerm() ?: return null } ?: return null
    return ImportedPerson(
        id = id,
        studentId = studentId,
        nickname = nickname,
        importedAt = Instant.ofEpochMilli(importedAtMillis),
        sharedAt = sharedAtMillis?.let(Instant::ofEpochMilli),
        terms = terms,
        placement = ImportedPerson.Placement.stored(placement),
    )
}

private fun ImportedTermDto.toTerm(): ImportedPerson.Term? {
    val semester = semester ?: return null
    val courseNos = courseNos?.map { it ?: return null } ?: return null
    val courses = courses?.filterNotNull() ?: return null
    return ImportedPerson.Term(semester, courseNos, DataCache.usableRows(courses))
}
