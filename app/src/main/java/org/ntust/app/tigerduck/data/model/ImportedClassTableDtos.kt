package org.ntust.app.tigerduck.data.model

import org.ntust.app.tigerduck.shared.Course

// The file ImportedClassTableStore keeps. Reference fields are nullable and the rest primitive:
// Gson's Unsafe path skips Kotlin defaults, so a key missing from a file reads as null or zero.
// R8 keeps these through the existing `data.model.**` keep rule.

/** [version] guards a later layout: a file of another version is kept as it is, not read. */
data class ImportedClassTablesFileDto(
    val version: Int,
    /** The student ID signed in when the people were imported. */
    val owner: String?,
    val people: List<ImportedPersonDto?>?,
)

data class ImportedPersonDto(
    val id: String?,
    val studentId: String?,
    val nickname: String?,
    val importedAtMillis: Long,
    val sharedAtMillis: Long?,
    val terms: List<ImportedTermDto?>?,
    /**
     * Where the class table switcher lists the person (`ImportedPerson.Placement.stored`). A file
     * from before placements has none, which reads as the rotation; a file this version writes
     * stays readable by an older one, which ignores the key and drops it on its next save.
     */
    val placement: String?,
)

data class ImportedTermDto(
    val semester: String?,
    val courseNos: List<String?>?,
    val courses: List<Course?>?,
)
