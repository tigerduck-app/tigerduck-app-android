package org.ntust.app.tigerduck.ui.screen.classtable.sharing

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.data.sharing.ClassTableShareCode
import org.ntust.app.tigerduck.data.sharing.ClassTableShareCodeError
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.logSummary

/**
 * One import of a class table from a share code: the code the camera or a picture gave, the
 * prompts it raises, the nickname, the course lookup and the person it saves. The import sheet,
 * the confirm screen and the prompts show it; a test passes its own lookup. The iOS app's
 * `ClassTableImportModel`, which shares its rules and its strings.
 */
class ClassTableImportModel(
    private val store: ImportedClassTableStore,
    private val signedInStudentId: () -> String?,
    /** False once the account the import began under has signed out, or another signed in. */
    private val isCurrent: () -> Boolean,
    /**
     * One term's courses from NTUST's course query, without the codes it does not know; throws
     * when the query fails.
     */
    private val lookup: suspend (semester: String, courseNos: List<String>) -> List<Course>,
    private val scope: CoroutineScope,
    /**
     * Opened by a link rather than the import menu: the page shows no camera or photo button, as
     * no one came to scan, and closes once the link's code is done with, saved or not.
     */
    fromLink: Boolean = false,
) {
    /** What a lookup imports: the code, and the nickname a new person is saved under. */
    data class ImportJob(val code: ClassTableShareCode, val nickname: String)

    /** The prompts that start a lookup carry its job, so their buttons need nothing else. */
    sealed interface Prompt {
        /** A wrong checksum, layout or course code. */
        data object Invalid : Prompt

        data object NoCode : Prompt

        /** A code a newer app wrote: a format version or a share type this one does not know. */
        data object NeedsUpdate : Prompt

        data object OwnCode : Prompt

        /** The code's student ID is imported already; updating keeps that person's nickname. */
        data class Replace(val job: ImportJob) : Prompt

        data class LookupFailed(val job: ImportJob) : Prompt

        data class SaveFailed(val job: ImportJob) : Prompt
    }

    /**
     * What the scanner's camera hears of a code it read ([scan]), which decides whether the camera
     * hands it over again while it stays in view ([RepeatedQrCodeFilter]).
     */
    enum class ScanAnswer {
        /** Taken: held while it stays in view, so cancelling its step does not reopen it. */
        TAKEN,

        /** Not a share code, such as another app's: held too, as it never becomes one. */
        NOT_SHARE_CODE,

        /** Something else is open: handed over again at the next frame, to be taken once it closes. */
        BUSY,

        /** The code the import is busy with, its step or prompt open: held, as when it was taken. */
        HOLDING,
    }

    data class State(
        val prompt: Prompt? = null,
        /** The code the confirm screen shows, with the nickname field above its details. */
        val pending: ClassTableShareCode? = null,
        val nickname: String = "",
        val isLookingUp: Boolean = false,
        /**
         * While the store writes the person, which nothing interrupts: Close, Cancel and back do
         * nothing then, as iOS refuses a swipe while busy, and the import ends saved.
         */
        val isSaving: Boolean = false,
        val isReadingImage: Boolean = false,
        /** Set once the person is saved, or once the account signed out meanwhile; the import closes then. */
        val isFinished: Boolean = false,
        val fromLink: Boolean = false,
        /** Set once a code was taken, so a link's page is not done before its code is read. */
        val tookCode: Boolean = false,
        /**
         * Set when a link opened another page while the person was being written
         * ([staysForLink]): saved, the import closes and leaves that page on screen, rather than
         * show the class table over it.
         */
        val leftForLink: Boolean = false,
    ) {
        val isIdle: Boolean get() = prompt == null && pending == null && !isLookingUp && !isReadingImage && !isFinished

        val canImport: Boolean get() = nickname.isNotBlank() && !isLookingUp && !isFinished

        /** A link's code is done with, called off or refused: its page closes rather than turn into a scanner. */
        val isLinkDone: Boolean get() = fromLink && tookCode && isIdle
    }

    private val _state = MutableStateFlow(State(fromLink = fromLink))
    val state: StateFlow<State> = _state.asStateFlow()

    private var lookupJob: Job? = null
    private var imageJob: Job? = null

    /**
     * The code the import is busy with, as the camera read it and as decoded, for [scan]'s
     * [ScanAnswer.HOLDING]; read only while the import is not idle.
     */
    private var heldText: String? = null
    private var heldCode: ClassTableShareCode? = null

    // --- Reading a code ---

    /**
     * A QR code a scanner that goes on scanning read. Another app's code is passed over so
     * scanning goes on, and so is any code while something else is on screen. Returns whether
     * the code was taken.
     */
    fun scanned(text: String): Boolean = scan(text) == ScanAnswer.TAKEN

    /** [scanned], with what the camera needs to know of a code it was not given ([ScanAnswer]). */
    fun scan(text: String): ScanAnswer {
        val idle = _state.value.isIdle
        val reading = reading(text, log = idle)
        if (reading == Reading.NotShareCode) return ScanAnswer.NOT_SHARE_CODE
        if (!idle) {
            val held = text == heldText || (reading is Reading.Code && reading.code == heldCode)
            return if (held) ScanAnswer.HOLDING else ScanAnswer.BUSY
        }
        when (reading) {
            Reading.Invalid -> _state.update { it.copy(prompt = Prompt.Invalid) }
            Reading.NeedsUpdate -> _state.update { it.copy(prompt = Prompt.NeedsUpdate) }
            is Reading.Code -> open(reading.code)
            Reading.NotShareCode -> Unit
        }
        heldText = text
        heldCode = (reading as? Reading.Code)?.code
        _state.update { it.copy(tookCode = true) }
        return ScanAnswer.TAKEN
    }

    /**
     * The code a link opened, read as [scanned] reads one, except that a link the decoder does not
     * take says the code is damaged: a link's page has no camera to try another code with.
     */
    fun linkOpened(text: String) {
        if (scanned(text)) return
        _state.update { it.copy(prompt = Prompt.Invalid, tookCode = true) }
    }

    /**
     * The QR codes found in a picture, or the one a scanner that stops after one code read: the
     * first share code, or a prompt when there is none.
     */
    fun imageRead(payloads: List<String>) {
        if (!_state.value.isIdle) return
        val readings = payloads.map { reading(it) }
        val code = readings.firstNotNullOfOrNull { (it as? Reading.Code)?.code }
        // The picture's code is the one held now; none the camera read before.
        heldText = null
        heldCode = code
        if (code != null) {
            open(code)
        } else {
            // A newer app's code is one this one could read once updated, so that comes first.
            val prompt = when {
                Reading.NeedsUpdate in readings -> Prompt.NeedsUpdate
                Reading.Invalid in readings -> Prompt.Invalid
                else -> Prompt.NoCode
            }
            _state.update { it.copy(prompt = prompt) }
        }
    }

    /**
     * Reads a picture: [read] returns its QR codes, or null when it could not be opened. The import
     * is held while it runs, so nothing else can start one.
     */
    fun readImage(read: suspend () -> List<String>?) {
        if (!_state.value.isIdle) return
        heldText = null
        heldCode = null
        _state.update { it.copy(isReadingImage = true) }
        imageJob = scope.launch {
            val payloads = try {
                read().orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A picture that could not be read holds no code the import can use.
                Log.w(TAG, "Could not read the picture: ${e.logSummary()}")
                emptyList()
            }
            _state.update { it.copy(isReadingImage = false) }
            imageJob = null
            if (!isCurrent()) {
                _state.update { it.copy(isFinished = true) }
                return@launch
            }
            imageRead(payloads)
        }
    }

    private fun open(code: ClassTableShareCode) {
        val studentId = code.studentId
        if (studentId != null) {
            if (studentId == signedInStudentId()?.uppercase()) {
                _state.update { it.copy(prompt = Prompt.OwnCode) }
                return
            }
            val person = store.tables.value.person(studentId)
            if (person != null) {
                _state.update { it.copy(prompt = Prompt.Replace(ImportJob(code, person.nickname))) }
                return
            }
        }
        _state.update { it.copy(pending = code, nickname = studentId.orEmpty()) }
    }

    // --- Importing ---

    fun setNickname(nickname: String) {
        _state.update { it.copy(nickname = nickname) }
    }

    fun importPending() {
        val state = _state.value
        val pending = state.pending ?: return
        if (!state.canImport) return
        lookUp(ImportJob(pending, state.nickname))
    }

    /**
     * Saves nothing until every term's lookup has answered, and nothing at all after a failure, a
     * cancel or a sign-out. A failed save keeps the confirm screen, with a prompt to try again.
     */
    fun lookUp(job: ImportJob) {
        val state = _state.value
        if (state.isLookingUp || state.isFinished) return
        _state.update { it.copy(prompt = null, isLookingUp = true) }
        lookupJob = scope.launch {
            val courses = courses(job.code)
            if (!isCurrent()) {
                _state.update { it.copy(isLookingUp = false, isFinished = true) }
                return@launch
            }
            if (courses == null) {
                _state.update { it.copy(isLookingUp = false, prompt = Prompt.LookupFailed(job)) }
                return@launch
            }
            // Past this point the write goes through whatever happens: cancel waits for it.
            _state.update { it.copy(isSaving = true) }
            val person = try {
                withContext(NonCancellable) { store.add(job.code, job.nickname, courses) }
            } finally {
                _state.update { it.copy(isSaving = false) }
            }
            if (person == null) {
                _state.update { it.copy(isLookingUp = false, prompt = Prompt.SaveFailed(job)) }
                return@launch
            }
            store.show(person.id)
            _state.update { it.copy(isLookingUp = false, isFinished = true) }
        }
    }

    private suspend fun courses(code: ClassTableShareCode): Map<String, List<Course>>? = try {
        code.terms.associate { it.semester to lookup(it.semester, it.courseNos) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Could not look up an imported class table: ${e.logSummary()}")
        null
    }

    /** Cancel on a failed lookup's prompt leaves the confirm screen as it was. */
    fun dismissPrompt() {
        _state.update { it.copy(prompt = null) }
    }

    /**
     * A lookup still running saves nothing once this returns, even if its answer comes later. Once
     * the person is being written it is too late: the import goes on, ends saved and closes, so
     * nothing is kept on the device that the screen said was called off.
     */
    fun cancel() {
        if (_state.value.isSaving) return
        imageJob?.cancel()
        imageJob = null
        lookupJob?.cancel()
        lookupJob = null
        _state.update { it.copy(prompt = null, pending = null, isLookingUp = false, isReadingImage = false) }
    }

    /**
     * A link from a reminder, a bulletin or School Mail opens another page: whether the import
     * stays over it. Only while the person is being written, which nothing stops, as Close does
     * nothing then: it ends saved, or says the write failed, with a retry ([State.leftForLink]).
     * At any other step the link calls it off, as Close does.
     */
    fun staysForLink(): Boolean {
        if (!_state.value.isSaving) return false
        _state.update { it.copy(leftForLink = true) }
        return true
    }

    // --- Decoding ---

    private sealed interface Reading {
        data object NotShareCode : Reading

        data object Invalid : Reading

        data object NeedsUpdate : Reading

        data class Code(val code: ClassTableShareCode) : Reading
    }

    /** [text] as the import reads it; [log] a refused code, though not one read again while the import is busy. */
    private fun reading(text: String, log: Boolean = true): Reading = try {
        Reading.Code(ClassTableShareCode.decode(text))
    } catch (_: ClassTableShareCodeError.UnknownPrefix) {
        Reading.NotShareCode
    } catch (_: ClassTableShareCodeError.UnknownVersion) {
        Reading.NeedsUpdate
    } catch (_: ClassTableShareCodeError.UnknownType) {
        Reading.NeedsUpdate
    } catch (e: ClassTableShareCodeError) {
        if (log) Log.i(TAG, "Share code refused: ${e.javaClass.simpleName}")
        Reading.Invalid
    }

    private companion object {
        const val TAG = "ClassTableImport"
    }
}
