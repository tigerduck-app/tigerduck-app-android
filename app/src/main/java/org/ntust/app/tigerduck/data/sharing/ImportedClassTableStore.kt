package org.ntust.app.tigerduck.data.sharing

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParseException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.ImportedClassTablesFileDto
import org.ntust.app.tigerduck.data.model.ImportedPersonDto
import org.ntust.app.tigerduck.data.model.ImportedTermDto
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.di.IoDispatcher
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.util.logSummary
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Someone whose class table the user imported from a share code. */
data class ImportedPerson(
    val id: String,
    /** Null when the share code carried none. */
    val studentId: String?,
    val nickname: String,
    val importedAt: Instant,
    val sharedAt: Instant?,
    val terms: List<Term>,
) {
    data class Term(
        /** The app's semester code, such as `1151`. */
        val semester: String,
        val courseNos: List<String>,
        /** The courses' names, times and rooms from NTUST's course query. */
        val courses: List<Course>,
    )

    /** Newest first, as the term picker lists them. */
    val semesters: List<String> get() = terms.map { it.semester }.sortedWith(org.ntust.app.tigerduck.shared.time.AcademicTerm.newestFirst)

    /** The term to open this class table on: [keeping] when the person shared it, else their latest. */
    fun semester(keeping: String): String =
        if (keeping in semesters) keeping else semesters.firstOrNull() ?: keeping

    fun courses(semester: String): List<Course> = terms.firstOrNull { it.semester == semester }?.courses.orEmpty()
}

/**
 * What a change to a person did. One deleted, or imported again, since the change began is
 * [SKIPPED], which is not a failure; after [FAILED] the store holds what it held before.
 */
enum class ImportedClassTableChange { SAVED, SKIPPED, FAILED }

/** What [ImportedClassTableStore] holds at one moment. */
data class ImportedClassTables(
    /** In import order. */
    val people: List<ImportedPerson> = emptyList(),
    /** Whose class table the class table shows, null for the user's own. */
    val shownPersonId: String? = null,
) {
    val shownPerson: ImportedPerson? get() = people.firstOrNull { it.id == shownPersonId }

    /** For the sign-out confirmation, which says the imported class tables go too. */
    val hasImportedClassTables: Boolean get() = people.isNotEmpty()

    /**
     * The NTUST account's sign-out deletes the imported class tables, so with any here it calls
     * [confirm] to ask first; otherwise it signs out at once with [signOut].
     */
    fun requestSignOut(confirm: () -> Unit, signOut: () -> Unit) {
        if (hasImportedClassTables) confirm() else signOut()
    }

    fun person(studentId: String): ImportedPerson? = people.firstOrNull { it.studentId == studentId }
}

/**
 * The class tables imported from share codes, kept on this device only: never in TigerSync, the
 * backend or the watch's data, and in the no-backup folder, away from backups. The user's sign-out
 * empties it, and so does another student ID signing in; a renewed session and TigerSync turned
 * off leave it alone. See [ClassTableShareCode] for the format.
 */
@Singleton
class ImportedClassTableStore internal constructor(
    locateFile: () -> File,
    private val signedInStudentId: () -> String?,
    private val ioDispatcher: CoroutineDispatcher,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        credentials: CredentialManager,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(
        locateFile = { fileIn(context) },
        signedInStudentId = { credentials.ntustStudentId },
        ioDispatcher = ioDispatcher,
    )

    // Resolved on the I/O dispatcher, as the first read of the folder creates it.
    private val file by lazy(locateFile)
    private val gson = Gson()
    private val mutex = Mutex()

    private val _tables = MutableStateFlow(ImportedClassTables())

    /**
     * Empty until [load] has read the file. The shown person is kept in memory only, so every
     * launch opens on the user's own class table.
     */
    val tables: StateFlow<ImportedClassTables> = _tables.asStateFlow()

    /** A class table that was shown: [id] is a person's, or null for the user's own. */
    private class Shown(val id: String?)

    // Guards the switches between class tables, which callers make from the main thread while a
    // deletion running in [locked] may move the class table back to the user's own.
    private val shownLock = Any()

    /** The class table shown before the current one; null before the first switch. */
    private var previousShown: Shown? = null

    // The fields below are read and written only inside [locked].
    private var loaded = false

    /** The student ID signed in when the people were imported. */
    private var owner: String? = null

    /**
     * Off after the file was there but could not be read or decoded, so no write replaces what
     * it holds, such as people a newer version of the app wrote.
     */
    private var mayWrite = true

    /** Reads the file the first time; every other call reads it first too. */
    suspend fun load(): ImportedClassTables = locked {
        loadIfNeeded()
        _tables.value
    }

    /**
     * Adds the person [code] is from, each term with its course details from [courses], keyed by
     * semester. A student ID already here keeps its entry, place and nickname, and takes the
     * code's terms, its share time and [importedAt]. Null when a new person's nickname is blank, or
     * when the file could not be written: nothing changes then, in memory or on disk.
     */
    suspend fun add(
        code: ClassTableShareCode,
        nickname: String,
        courses: Map<String, List<Course>>,
        importedAt: Instant = AppClock.instant(),
    ): ImportedPerson? = locked {
        loadIfNeeded()
        val account = signedInStudentId()
        if (account != null) accountDidSignInLocked(account)
        // Copies, so a caller reusing its lists cannot change what the store holds and wrote.
        val terms = code.terms.map {
            ImportedPerson.Term(it.semester, it.courseNos.toList(), courses[it.semester].orEmpty().toList())
        }
        // The file keeps milliseconds, so the person read back equals this one.
        val at = importedAt.truncatedTo(ChronoUnit.MILLIS)

        val people = _tables.value.people
        val index = code.studentId?.let { studentId -> people.indexOfFirst { it.studentId == studentId } } ?: -1
        val person: ImportedPerson
        val updated: List<ImportedPerson>
        if (index >= 0) {
            person = people[index].copy(importedAt = at, sharedAt = code.sharedAt, terms = terms)
            updated = people.mapIndexed { i, old -> if (i == index) person else old }
        } else {
            val name = nickname.trim()
            if (name.isEmpty()) return@locked null
            person = ImportedPerson(UUID.randomUUID().toString(), code.studentId, name, at, code.sharedAt, terms)
            updated = people + person
        }
        val newOwner = account ?: owner
        if (!save(updated, newOwner)) return@locked null
        owner = newOwner
        _tables.update { it.copy(people = updated) }
        person
    }

    suspend fun rename(id: String, nickname: String): ImportedClassTableChange = locked {
        loadIfNeeded()
        val name = nickname.trim()
        val people = _tables.value.people
        if (name.isEmpty() || people.none { it.id == id }) return@locked ImportedClassTableChange.SKIPPED
        val updated = people.map { if (it.id == id) it.copy(nickname = name) else it }
        if (!save(updated, owner)) return@locked ImportedClassTableChange.FAILED
        _tables.update { it.copy(people = updated) }
        ImportedClassTableChange.SAVED
    }

    /**
     * Swaps in a new lookup of [courseNos], one term's course codes, for that term of [id] as
     * imported at [importedAt]. A person imported again while the lookup ran, with the same codes
     * or others, keeps the new import's courses.
     */
    suspend fun updateCourses(
        id: String,
        semester: String,
        courseNos: List<String>,
        courses: List<Course>,
        importedAt: Instant,
    ): ImportedClassTableChange = locked {
        loadIfNeeded()
        val people = _tables.value.people
        val old = people.firstOrNull { it.id == id }
        if (old == null || old.importedAt != importedAt ||
            old.terms.none { it.semester == semester && it.courseNos == courseNos }
        ) {
            return@locked ImportedClassTableChange.SKIPPED
        }
        val terms = old.terms.map {
            if (it.semester == semester) ImportedPerson.Term(semester, courseNos.toList(), courses.toList()) else it
        }
        val updated = people.map { if (it.id == id) old.copy(terms = terms) else it }
        if (!save(updated, owner)) return@locked ImportedClassTableChange.FAILED
        _tables.update { it.copy(people = updated) }
        ImportedClassTableChange.SAVED
    }

    /** Removes [id]; the class table goes back to the user's own if [id]'s was shown. */
    suspend fun delete(id: String): ImportedClassTableChange = locked {
        loadIfNeeded()
        val people = _tables.value.people
        if (people.none { it.id == id }) return@locked ImportedClassTableChange.SKIPPED
        val updated = people.filter { it.id != id }
        val newOwner = if (updated.isEmpty()) null else owner
        if (!save(updated, newOwner)) return@locked ImportedClassTableChange.FAILED
        owner = newOwner
        synchronized(shownLock) {
            // One update, so no one reads the class table of someone already gone.
            if (_tables.value.shownPersonId == id) previousShown = Shown(id)
            _tables.update { tables ->
                tables.copy(people = updated, shownPersonId = tables.shownPersonId.takeUnless { it == id })
            }
        }
        ImportedClassTableChange.SAVED
    }

    /** Shows [id]'s class table, or the user's own for null; an id not in the store is ignored. */
    fun show(id: String?) {
        synchronized(shownLock) {
            if (id == null || _tables.value.people.any { it.id == id }) setShownLocked(id)
        }
    }

    /**
     * Shows the class table shown before the current one, or the next one when there is none to
     * go back to: before the first switch, or once that person is deleted.
     */
    fun showPrevious() {
        synchronized(shownLock) {
            val previous = previousShown
            if (previous != null && (previous.id == null || _tables.value.people.any { it.id == previous.id })) {
                setShownLocked(previous.id)
            } else {
                showNextLocked()
            }
        }
    }

    /**
     * Shows the class table after the shown one: the user's own, then each person in import
     * order, then the user's own again.
     */
    fun showNext() {
        synchronized(shownLock) { showNextLocked() }
    }

    private fun showNextLocked() {
        val order = listOf<String?>(null) + _tables.value.people.map { it.id }
        val index = order.indexOf(_tables.value.shownPersonId).coerceAtLeast(0)
        setShownLocked(order[(index + 1) % order.size])
    }

    /** Called holding [shownLock]; remembers the class table shown until now for [showPrevious]. */
    private fun setShownLocked(id: String?) {
        val current = _tables.value.shownPersonId
        if (current == id) return
        previousShown = Shown(current)
        _tables.update { it.copy(shownPersonId = id) }
    }

    /** For the user's own sign-out: a renewed session or a relogin in the background must not call it. */
    suspend fun clearForSignOut() {
        locked { clear() }
    }

    /**
     * Another student ID than the one the people were imported under empties the store; a store
     * filled while no one was signed in becomes this account's.
     */
    suspend fun accountDidSignIn(studentId: String) {
        locked {
            loadIfNeeded()
            accountDidSignInLocked(studentId)
        }
    }

    // A block runs on the I/O dispatcher and never suspends, so a caller cancelled once it has
    // started still finds what it changed in memory and on disk alike.
    private suspend fun <T> locked(block: () -> T): T = mutex.withLock { withContext(ioDispatcher) { block() } }

    private fun accountDidSignInLocked(studentId: String) {
        if (_tables.value.people.isEmpty()) return
        val current = owner
        if (current == null) {
            owner = studentId
            save(_tables.value.people, studentId)
        } else if (current != studentId) {
            clear()
        }
    }

    private fun clear() {
        loaded = true
        owner = null
        mayWrite = true
        synchronized(shownLock) {
            previousShown = null
            _tables.value = ImportedClassTables()
        }
        if (file.exists() && !file.delete()) Log.w(TAG, "Could not delete the imported class tables")
        // A write a process death cut short leaves its temporary file, with the tables it held.
        unfinishedWrite().delete()
    }

    // --- File ---

    private fun loadIfNeeded() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        val text = try {
            file.readText()
        } catch (e: IOException) {
            mayWrite = false
            Log.w(TAG, "Could not read the imported class tables: ${e.logSummary()}")
            return
        }
        val stored = decoded(text)
        if (stored == null) {
            mayWrite = false
            Log.w(TAG, "The imported class tables did not decode; the file is kept as it is")
            return
        }
        // The app may have stopped before a sign-out, or another account's sign-in, emptied the
        // store. Imports made while no one was signed in have no owner and stay.
        if (stored.owner != null && stored.owner != signedInStudentId()) {
            clear()
            return
        }
        owner = stored.owner
        _tables.update { it.copy(people = stored.people) }
    }

    private class Stored(val owner: String?, val people: List<ImportedPerson>)

    /** Null for a file this version did not write, as its people would not survive a save. */
    private fun decoded(text: String): Stored? {
        val stored = try {
            gson.fromJson(text, ImportedClassTablesFileDto::class.java)
        } catch (_: JsonParseException) {
            null
        }
        if (stored == null || stored.version != FILE_VERSION) return null
        val people = stored.people?.map { it?.toPerson() ?: return null } ?: return null
        return Stored(stored.owner, people)
    }

    /** Where [save] writes before it renames the file into place. */
    private fun unfinishedWrite() = File(file.parentFile, "${file.name}.tmp")

    /**
     * Writes [people] under [owner] and says whether the file now holds them. A caller publishes a
     * change only after this, so the people on screen are always the ones a relaunch reads back.
     */
    private fun save(people: List<ImportedPerson>, owner: String?): Boolean {
        if (!mayWrite) return false
        val stored = ImportedClassTablesFileDto(FILE_VERSION, owner, people.map { it.toDto() })
        // Written whole and moved over the old file, so a crash mid-write leaves the last good one.
        val tmp = unfinishedWrite()
        return try {
            file.parentFile?.mkdirs()
            tmp.writeText(gson.toJson(stored))
            if (tmp.renameTo(file)) {
                true
            } else {
                Log.w(TAG, "Could not replace the imported class tables")
                tmp.delete()
                false
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not write the imported class tables: ${e.logSummary()}")
            tmp.delete()
            false
        }
    }

    internal companion object {
        private const val TAG = "ImportedClassTables"
        private const val FILE_VERSION = 1

        /** In the no-backup folder, so neither a backup nor a restore carries other people's class tables. */
        internal fun fileIn(context: Context) = File(context.noBackupFilesDir, "imported_class_tables.json")
    }
}

private fun ImportedPerson.toDto() = ImportedPersonDto(
    id = id,
    studentId = studentId,
    nickname = nickname,
    importedAtMillis = importedAt.toEpochMilli(),
    sharedAtMillis = sharedAt?.toEpochMilli(),
    terms = terms.map { ImportedTermDto(it.semester, it.courseNos, it.courses) },
)

/** Null when a key the store always writes is missing. */
private fun ImportedPersonDto.toPerson(): ImportedPerson? {
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
    )
}

private fun ImportedTermDto.toTerm(): ImportedPerson.Term? {
    val semester = semester ?: return null
    val courseNos = courseNos?.map { it ?: return null } ?: return null
    val courses = courses?.filterNotNull() ?: return null
    return ImportedPerson.Term(semester, courseNos, DataCache.usableRows(courses))
}
