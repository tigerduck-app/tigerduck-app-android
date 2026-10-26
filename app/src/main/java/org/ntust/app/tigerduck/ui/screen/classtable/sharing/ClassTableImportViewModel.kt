package org.ntust.app.tigerduck.ui.screen.classtable.sharing

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
import org.ntust.app.tigerduck.data.sharing.ImportedCourseLookup
import org.ntust.app.tigerduck.data.sharing.QrCodeImageReader
import javax.inject.Inject

/**
 * The app's one class table import, which the class table's ⋯ menu and the Class Table tab's
 * menu both start. Held at the activity, above the pages, so either can start it from any tab.
 */
@HiltViewModel
class ClassTableImportViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val store: ImportedClassTableStore,
    private val authService: AuthService,
    private val lookup: ImportedCourseLookup,
) : ViewModel() {
    private val _session = MutableStateFlow<ClassTableImportModel?>(null)

    /** The import under way, null while none is. */
    val session: StateFlow<ClassTableImportModel?> = _session.asStateFlow()

    init {
        // The prompts ask the store who is imported already.
        viewModelScope.launch { store.load() }
        viewModelScope.launch {
            // A sign-out ends an import: it would save into the store the sign-out empties.
            authService.authState.collect { signedIn -> if (!signedIn) end() }
        }
    }

    /**
     * Starts an import for whoever is signed in now, or for no one, as the tab's menu can start one
     * signed out; it saves nothing once someone signs out or in meanwhile.
     */
    fun start() {
        if (_session.value != null) return
        _session.value = newSession()
    }

    /**
     * Reads [text], the share code a link opened, as a scanned code: the same prompts, the
     * nickname, and nothing saved without the confirm step. Any web page or app can open such a
     * link. An import already open is replaced (decided for both apps): its code is dropped, and
     * this one is read in a new import, under whoever is signed in now.
     */
    fun openLink(text: String) {
        pendingLink?.cancel()
        val asOpened = sameSignIn({ authService.authState.value }, { authService.storedStudentId })
        pendingLink = viewModelScope.launch {
            linkedImport(store, { _session.value }, { newSession(fromLink = true) }, text, asOpened)?.let { _session.value = it }
        }
    }

    // A link still waiting for the store's first read; a sign-out calls it off with the import.
    private var pendingLink: Job? = null

    private fun newSession(fromLink: Boolean = false) = ClassTableImportModel(
        store = store,
        signedInStudentId = { authService.storedStudentId },
        isCurrent = sameSignIn({ authService.authState.value }, { authService.storedStudentId }),
        lookup = lookup::courses,
        scope = viewModelScope,
        fromLink = fromLink,
    )

    internal companion object {
        /**
         * A check that whoever was signed in when it was made, or no one, still is: an import
         * saves only then, as a sign-out empties the store and another account must not inherit it.
         */
        fun sameSignIn(signedIn: () -> Boolean, studentId: () -> String?): () -> Boolean {
            val startedAs = signedIn() to studentId()
            return { (signedIn() to studentId()) == startedAs }
        }

        /**
         * The import that reads [text], the share code a link opened: [next], in place of [open],
         * the import on screen if any. That one is called off as its Cancel does: its code and
         * prompt are dropped, and a lookup under way saves nothing, though a person already being
         * written still ends saved, which Cancel cannot stop either. Unlike a scan, the link's code
         * is never passed over for what was on screen.
         *
         * Waits for [store]'s first read: a link can start the app, and so this view model, before
         * it is done, and the prompts ask it who is imported already. Read sooner, a person the
         * store holds would get the new person's confirm step instead of the update prompt.
         */
        suspend fun linkedImport(
            store: ImportedClassTableStore,
            open: () -> ClassTableImportModel?,
            next: () -> ClassTableImportModel,
            text: String,
            stillCurrent: () -> Boolean = { true },
        ): ClassTableImportModel? {
            store.load()
            // Signed out, or someone else signed in, while the store was read: the link is dropped,
            // as one held for a sign-in is at a sign-out.
            if (!stillCurrent()) return null
            open()?.cancel()
            return next().also { it.linkOpened(text) }
        }
    }

    fun end() {
        pendingLink?.cancel()
        pendingLink = null
        _session.value?.cancel()
        _session.value = null
    }

    /**
     * A link opens another page over the import: it ends, as its Close does, unless the person is
     * being written ([ClassTableImportModel.staysForLink]). A share link still waiting goes either
     * way, as the link that came after it wins.
     */
    fun closeForLink() {
        if (_session.value?.staysForLink() != true) return end()
        pendingLink?.cancel()
        pendingLink = null
    }

    /** Reads the QR codes in the picture the photo picker returned. */
    fun readPicture(uri: Uri) {
        _session.value?.readImage {
            withContext(Dispatchers.Default) { QrCodeImageReader.read(context, uri) }
        }
    }
}
