package org.ntust.app.tigerduck.demo

import android.content.Context
import android.util.Log
import androidx.core.os.ConfigurationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.auth.session.AccountSessions
import org.ntust.app.tigerduck.auth.session.StartsAccountWork
import org.ntust.app.tigerduck.data.cache.BulletinCache
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.sharing.ClassTableShareCode
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
import org.ntust.app.tigerduck.debug.DebugFixtureStore
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.network.MoodleCourseIds
import org.ntust.app.tigerduck.network.moodle.MoodleCourseApi
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.ui.component.ServerStatusTracker
import org.ntust.app.tigerduck.util.logSummary
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The store-review account. Signing in with the student ID and school
 * password in `assets/demo.json` puts the app on that file's made-up student
 * and never contacts a server — not NTUST, not Moodle, not the library, not
 * ours. The library sign-in takes the same ID with the file's library
 * password, with or without the demo account signed in ([signInLibrary]).
 *
 * Play review has no NTUST account to sign in with and a real one cannot be
 * handed out, so without this a reviewer sees nothing past the sign-in page.
 *
 * How: [DebugFixtureStore.demoMode] makes
 * [org.ntust.app.tigerduck.debug.DemoModeInterceptor] refuse every request on
 * every OkHttp client, the file's data goes into the same caches the real
 * fetches fill, and [ServerStatusTracker] holds every status dot at OK. Each
 * screen then takes the offline path it already has, over the demo data.
 * Signing out leaves it. The school session answers whether the app is in the
 * demo ([org.ntust.app.tigerduck.auth.session.AccountSession.isDemo]): this
 * class moves it, and the stored flag it starts from at the next process.
 */
@Singleton
class DemoAccount @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val store: DebugFixtureStore,
    private val dataCache: DataCache,
    private val bulletinCache: BulletinCache,
    private val appPreferences: AppPreferences,
    private val importedClassTables: ImportedClassTableStore,
    private val sessions: AccountSessions,
) {
    private val isActive: Boolean get() = sessions.school.isDemo

    /** Whether these are the demo account's credentials. Reads the bundled file and nothing else. */
    fun matches(studentId: String, password: String): Boolean =
        load(DemoFixture.LANG_EN)?.matches(studentId, password) == true

    /**
     * Whether the demo library is signed in: through the file's
     * `libraryQr.fakeLoggedIn` at the demo sign-in, or a library sign-in with
     * its library password, with or without the demo account.
     */
    val isLibrarySignedIn: Boolean
        get() = store.libraryQrContent != null && store.libraryFakeSignedIn

    /** The library user name to show while [isLibrarySignedIn], or null. */
    val libraryUsername: String?
        get() = if (isLibrarySignedIn) store.libraryUsername ?: store.studentIdOverride else null

    /**
     * The demo library sign-in. Signs the library in, on the file's QR, when
     * these are its library credentials, the ID normalized as for the school
     * sign-in; contacts nothing either way, and needs no demo account. The
     * school side is untouched, so a real or absent NTUST account stays as
     * it is. Returns whether it signed in.
     */
    fun signInLibrary(username: String, password: String): Boolean {
        val fixture = load(DemoFixture.LANG_EN) ?: return false
        val qr = fixture.libraryQrContent
        if (qr == null || !fixture.matchesLibrary(username, password)) return false
        store.libraryQrContent = qr
        store.libraryUsername = fixture.studentId?.trim()?.uppercase()
        store.libraryFakeSignedIn = true
        return true
    }

    /**
     * The demo library sign-out. Also run after every real library sign-in:
     * otherwise the library screen goes on showing the demo QR and user name
     * in place of the real account's.
     */
    fun signOutLibrary() {
        store.libraryFakeSignedIn = false
        store.libraryUsername = null
        // Outside the demo account nothing else wants the demo QR, and left
        // behind it would stand in for a real library account's QR.
        if (!isActive) store.libraryQrContent = null
    }

    /**
     * Switch to the demo account, in place of the sign-in [from], and hand back the sign-in that
     * follows it; null, with nothing changed, when a sign-out has ended [from]. Demo mode goes on
     * first, then the session's answer, so nothing that starts after this can reach a server and
     * overwrite the data written next. All that the demo's sign-out takes away goes on in one step
     * against that sign-out's end of [from]: it came first, and nothing is left behind it, or it
     * clears all of this after.
     */
    suspend fun enter(from: SignIn): SignIn? {
        val fixture = load(currentLang()) ?: return from
        val signIn = sessions.school.startDemo(from) {
            store.demoMode = true
            store.studentIdOverride = fixture.studentId
            store.libraryQrContent = fixture.libraryQrContent
            store.libraryFakeSignedIn = fixture.libraryFakeSignedIn
            ServerStatusTracker.enterDemoMode()
        } ?: return null
        write(fixture, signIn)
        return signIn
    }

    /**
     * Write the demo data again, on each app start while signed in: in the
     * language the app now shows, and over anything a request already in
     * flight at sign-in may have written after it.
     */
    @OptIn(StartsAccountWork::class)
    suspend fun reapply() {
        val signIn = sessions.school.current
        if (!signIn.isDemo) return
        load(currentLang())?.let { write(it, signIn) }
    }

    /**
     * Leave the demo account. Part of the school sign-out, which clears the cached data itself; a
     * demo library signed in stays, as a real library account does, until [signOutLibrary]. The
     * stored flag first: one that would not clear leaves the session's answer as the flag reads,
     * which the next start tries again from, and School Mail, which only that answer keeps shut,
     * shut until then.
     */
    fun exit() {
        store.clearSchool()
        sessions.school.signIns.leaveDemo()
        ServerStatusTracker.exitDemoMode()
    }

    /**
     * The demo account's Moodle, which answers from the bundled file; null outside the demo
     * account, or for a file without a `moodle` section.
     */
    fun moodleApi(): MoodleCourseApi? {
        if (!isActive) return null
        val moodle = load(currentLang())?.moodle ?: return null
        return DemoMoodleCourseApi(
            moodle,
            readIds = { store.demoMoodleReadIds },
            markRead = { store.demoMoodleReadIds = store.demoMoodleReadIds + it },
        )
    }

    private suspend fun write(fixture: DemoFixture, signIn: SignIn) {
        fixture.courses?.let { dataCache.saveCourses(it, signIn) }
        // The ids the course detail finds the demo courses' Moodle content under. A course has
        // no idnumber of its own, so it is looked up as `<term><courseNo>`, as one added by hand.
        fixture.moodle?.let { moodle ->
            val term = dataCache.currentSemesterCode()
            val ids = moodle.courses.associate {
                MoodleCourseIds.normalizedIdnumber(term + it.courseNo) to it.moodleId
            }
            dataCache.saveMoodleCourseIds(dataCache.loadMoodleCourseIds() + ids, signIn)
        }
        fixture.assignments?.let { dataCache.saveAssignments(it, signIn) }
        // The assignments' pages read these, as a round would have kept them.
        fixture.moodle?.let { dataCache.saveAssignmentRecords(DemoMoodleFixture.records(it, fixture.assignments.orEmpty()), signIn) }
        // Only while still this sign-in, asked under the cache's lock: a sign-out ends it before
        // its clear, which a write let through earlier lands before.
        fixture.bulletins?.let { bulletins -> bulletinCache.saveIf(bulletins, fetchedAt = null) { signIn.isCurrent } }
        fixture.calendar?.let { dataCache.saveCalendarEvents(it, signIn) }
    }

    /**
     * The classmates' class tables, imported as their share codes would import them, on the term
     * the demo courses are in. Called by the demo sign-in once the store knows the demo account
     * signed in, so another account's leftovers are gone first. Only at sign-in, not on each
     * start: a classmate the reviewer deleted stays deleted. A sign-out empties them with every
     * other import, and one past [signIn]'s end, whose import would be the next student's, is
     * not imported.
     */
    suspend fun importClassmates(signIn: SignIn) {
        val fixture = load(currentLang()) ?: return
        val classmates = fixture.classmates ?: return
        importDemoClassmates(
            importedClassTables, dataCache.currentSemesterCode(), classmates,
            AppClock.instant().truncatedTo(ChronoUnit.MINUTES),
        ) { signIn.isCurrent }
    }

    private fun load(lang: String): DemoFixture? = runCatching {
        context.assets.open(ASSET).bufferedReader().use { DemoFixture.parse(it.readText(), lang) }
    }.onFailure { Log.e(TAG, "cannot read $ASSET: ${it.logSummary()}") }.getOrNull()

    /** The language the app is showing, resolved the way push registration resolves it. */
    private fun currentLang(): String {
        val locale = AppLanguageManager.resolveExplicitLocale(appPreferences.appLanguage)
            ?: ConfigurationCompat.getLocales(context.resources.configuration)[0]
        return if (locale?.language == "zh") DemoFixture.LANG_ZH else DemoFixture.LANG_EN
    }

    private companion object {
        const val TAG = "DemoAccount"
        const val ASSET = "demo.json"
    }
}

/**
 * Imports [classmates] into [store] on [semester], each as its share code shared at
 * [sharedAt] would import it: the course codes NTUST's query can look up, with their details;
 * each while [stillCurrent] says so ([ImportedClassTableStore.add]).
 */
internal suspend fun importDemoClassmates(
    store: ImportedClassTableStore,
    semester: String,
    classmates: List<DemoFixture.Classmate>,
    sharedAt: Instant,
    stillCurrent: () -> Boolean = { true },
) {
    for (classmate in classmates) {
        val courses = classmate.courses.filter { CourseService.isCourseNo(it.courseNo) }
        val code = ClassTableShareCode(
            ClassTableShareCode.Format.TIGER_DUCK,
            classmate.studentId,
            sharedAt,
            listOf(ClassTableShareCode.Term(semester, courses.map { it.courseNo })),
        )
        store.add(code, classmate.nickname, mapOf(semester to courses), stillCurrent = stillCurrent)
    }
}

