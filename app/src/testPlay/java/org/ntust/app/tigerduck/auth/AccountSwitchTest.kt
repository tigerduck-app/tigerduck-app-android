package org.ntust.app.tigerduck.auth

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.HeldShareLink
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.analytics.AnalyticsLogger
import org.ntust.app.tigerduck.browser.BrowserCleanup
import org.ntust.app.tigerduck.browser.BrowserDataCleaner
import org.ntust.app.tigerduck.browser.BrowserDownloads
import org.ntust.app.tigerduck.browser.BrowserSettings
import org.ntust.app.tigerduck.browser.StorageClearing
import org.ntust.app.tigerduck.data.BulletinReadStateStore
import org.ntust.app.tigerduck.data.cache.BulletinCache
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.data.preferences.UiLanguageMonitor
import org.ntust.app.tigerduck.data.sharing.ClassTableShareCode
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
import org.ntust.app.tigerduck.debug.DebugFixtureStore
import org.ntust.app.tigerduck.debug.DemoModeInterceptor
import org.ntust.app.tigerduck.demo.DemoAccount
import org.ntust.app.tigerduck.emptyclassroom.ClassroomDemoData
import org.ntust.app.tigerduck.emptyclassroom.ClassroomUsageService
import org.ntust.app.tigerduck.liveactivity.LiveActivityBoundaryScheduler
import org.ntust.app.tigerduck.liveactivity.LiveActivityManager
import org.ntust.app.tigerduck.liveactivity.LiveActivityNotifier
import org.ntust.app.tigerduck.liveactivity.LiveActivityPreferences
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.network.MoodleService
import org.ntust.app.tigerduck.network.MoodleTokenPairStore
import org.ntust.app.tigerduck.network.MoodleTokenService
import org.ntust.app.tigerduck.network.NtustSessionManager
import org.ntust.app.tigerduck.network.SsoLoginService
import org.ntust.app.tigerduck.network.moodle.MoodleWebservice
import org.ntust.app.tigerduck.notification.AssignmentNotificationScheduler
import org.ntust.app.tigerduck.notification.ClassPreparingNotificationScheduler
import org.ntust.app.tigerduck.notification.NotificationChannelRegistrar
import org.ntust.app.tigerduck.push.DepartingDeviceUnregister
import org.ntust.app.tigerduck.push.NotificationSettingsSync
import org.ntust.app.tigerduck.push.OwedUnregister
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.push.PushIdentity
import org.ntust.app.tigerduck.push.PushRegistrationService
import org.ntust.app.tigerduck.push.SettingsDocumentApiClient
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.widget.WidgetBoundaryScheduler
import org.ntust.app.tigerduck.widget.WidgetUpdater
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.Date

/**
 * One student has used the app, keeping what docs/account-state.md gives the school account; the
 * student signs out, and another signs in: nothing of the first is left, and the first one's
 * School Mail and library account stay, as they sign out by themselves. Runs the real
 * [AuthService.logout], [AuthService.login] against hand-made school pages, and
 * [AccountSessionEffects], which follows both, over the real stores.
 *
 * Every key the inventory gives the school account is filled, typed as its source reads it, and
 * every DataCache file it lists, in the folder DataCache keeps it in. Nothing reaches a network:
 * the school is a fake, every other request is refused, and the registration and the settings
 * pushes run on a scope that never runs them. Play only: the widgets' analytics logger differs by
 * flavor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AccountSwitchTest {

    private val context = RuntimeEnvironment.getApplication()
    private val inventory = AccountStateInventory.app

    /** The sign-out's own clearing, waited for; work that would reach a network goes nowhere. */
    private val signOutJob = SupervisorJob()
    private val signOutScope = CoroutineScope(signOutJob + Dispatchers.IO)
    private val effectsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val idleScope = CoroutineScope(Job().apply { cancel() })

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @After
    fun tearDown() {
        effectsScope.cancel()
        signOutScope.cancel()
    }

    private fun prefs(file: String): SharedPreferences = context.getSharedPreferences(file, Context.MODE_PRIVATE)

    /** What the school account keeps under a key: each key of its own, and each of a file of its own. */
    private val schoolKeys: List<AccountStateInventory.Key> by lazy {
        inventory.rows.filter { it.scope == "school" }.flatMap { row ->
            when (row.store) {
                "prefs" -> inventory.keys[row.name].orEmpty().filter { it.type != null }
                "cache", "database" -> emptyList()
                else -> listOfNotNull(inventory.keys[row.store].orEmpty().firstOrNull { it.name == row.name && it.type != null })
            }
        }
    }

    /** The key [key] is filled under: a family's under a term. */
    private fun stored(key: AccountStateInventory.Key) = key.name.replace("*", SEMESTER)

    /** A value of [key]'s type that is not its default. */
    private fun sample(key: AccountStateInventory.Key): Any = when (key.type) {
        "Boolean" -> key.default != "true"
        "Int" -> 7
        "Long" -> 7L
        "Float" -> 1.5f
        "StringSet" -> setOf("1")
        else -> "$FIRST's"
    }

    private fun SharedPreferences.Editor.put(name: String, value: Any): SharedPreferences.Editor = when (value) {
        is Boolean -> putBoolean(name, value)
        is Int -> putInt(name, value)
        is Long -> putLong(name, value)
        is Float -> putFloat(name, value)
        is Set<*> -> putStringSet(name, value.map { it.toString() }.toSet())
        else -> putString(name, value.toString())
    }

    private val cacheRoots by lazy {
        mapOf(
            "cache" to File(context.cacheDir, "TigerDuckCache"),
            "data" to File(context.filesDir, "TigerDuckData"),
            "files" to context.filesDir,
        )
    }

    /**
     * A file of the first student's for each DataCache row, where DataCache keeps it: a family's
     * under a term, a folder's with a file in it, and a write cut short beside a file in each of
     * DataCache's folders.
     */
    private fun fillDataCache(): List<File> = inventory.rows.filter { it.store == "cache" && it.scope == "school" }.flatMap { row ->
        val where = inventory.cacheRoot(row.name)
        assertNotNull("where DataCache keeps ${row.name}", where)
        val root = cacheRoots.getValue(where!!)
        when {
            row.name == "*.tmp" -> listOf(
                File(cacheRoots.getValue("cache"), "course_lookups.json.tmp"),
                File(cacheRoots.getValue("data"), "unsent_marks.json.tmp"),
            )
            row.name.endsWith("/") -> listOf(File(root, row.name + "7/slides.pdf"))
            else -> listOf(File(root, row.name.replace("*", SEMESTER)))
        }.onEach {
            it.parentFile?.mkdirs()
            it.writeText("[]")
        }
    }

    private fun filesUnder(root: File): List<String> =
        root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.toList()

    /** The school's pages: its sign-in form for every visit, and the service once a password is posted. */
    private val school = OkHttpClient.Builder().addInterceptor { chain -> schoolPage(chain.request()) }.build()

    /** Every other server, which no request of this test reaches. */
    private val refusing = OkHttpClient.Builder().addInterceptor { throw IOException("no network in this test") }.build()

    private fun schoolPage(request: Request): Response {
        val (url, html) = if (request.method == "POST") SERVICE to SERVICE_PAGE else LOGIN_URL to LOGIN_PAGE
        return Response.Builder()
            // As if OkHttp had followed the redirect to it.
            .request(request.newBuilder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(html.toResponseBody("text/html; charset=utf-8".toMediaType()))
            .build()
    }

    @Test
    fun `signing in as a second student after a first leaves nothing of the first`() = runBlocking {
        // The first student signed in, with a School Mail and a library account of their own.
        schoolKeys.groupBy { it.file }.forEach { (file, keys) ->
            prefs(file).edit().apply { keys.forEach { put(stored(it), sample(it)) } }.commit()
        }
        val credentialPrefs = prefs("tigerduck_credentials")
        credentialPrefs.edit()
            .putString("ntust_student_id", FIRST)
            .putString("ntust_password", "first-password")
            .putString("mail_student_id", FIRST)
            .putString("mail_password", "mail-password")
            .putString("library_username", FIRST)
            .putString("library_password", "library-password")
            .commit()
        val othersBefore = credentialPrefs.all.filterKeys { it.startsWith("mail_") || it.startsWith("library_") }
        val firstLeft = schoolKeys.associate { (it.file to stored(it)) to prefs(it.file).all[stored(it)] }
        // The keys of each file the inventory gives the school account, the credentials among them.
        assertTrue(
            firstLeft.keys.map { it.first }.toSet()
                .containsAll(setOf("tigerduck_prefs", "tigerduck_credentials", "tigerduck_auth", "tigerduck_browser_settings")),
        )
        assertTrue(firstLeft.values.none { it == null })
        assertTrue(fillDataCache().all(File::exists))

        val appPrefs = AppPreferences(context)
        val credentials = CredentialManager(credentialPrefs)
        val fixtures = DebugFixtureStore(context)
        val academicCalendar = AcademicCalendarStore(context, refusing, appPrefs)
        val dataCache = DataCache(context, academicCalendar)
        val bulletinCache = BulletinCache(context)
        val imported = ImportedClassTableStore({ ImportedClassTableStore.fileIn(context) }, { credentials.ntustStudentId }, Dispatchers.Unconfined)
        val demoAccount = DemoAccount(context, fixtures, dataCache, bulletinCache, appPrefs, imported)
        val sessionManager = NtustSessionManager(appPrefs, DemoModeInterceptor(fixtures))
        val latch = PersistedStoredPasswordLatch(context, credentials)
        val sso = SsoLoginService(sessionManager, latch) { school }
        val tokens = AuthTokenManager(credentials.v3Tokens, refusing, appPrefs, "device-1")
        // As its Hilt constructor builds it, over the refusing client.
        val moodleTokens = MoodleTokenService(
            clientFor = { refusing },
            tokenStore = object : MoodleTokenPairStore {
                override var token: String?
                    get() = credentials.moodleToken
                    set(value) {
                        credentials.moodleToken = value
                    }
                override var privateToken: String?
                    get() = credentials.moodlePrivateToken
                    set(value) {
                        credentials.moodlePrivateToken = value
                    }
                override val studentId: String? get() = credentials.ntustStudentId
                override val password: String? get() = credentials.ntustPassword
                override fun storeIfSignedIn(studentId: String, token: String, privateToken: String?): Boolean =
                    credentials.storeMoodleTokenIfSignedIn(studentId, token, privateToken)
            },
            passwordLatch = latch,
            scope = idleScope,
            now = System::currentTimeMillis,
        )
        val courseService = CourseService(context, sessionManager, sso, dataCache, appPrefs, academicCalendar, idleScope)
        val uiLanguage = UiLanguageMonitor(appPrefs)
        val pushApi = PushApiClient(refusing, appPrefs, tokens)
        val liveUpdate = LiveActivityPreferences(context)
        val assignmentReminders = AssignmentNotificationScheduler(context)
        val auth = AuthService(
            context = context,
            sessionManager = sessionManager,
            ssoLoginService = sso,
            credentials = credentials,
            pushRegistration = PushRegistrationService(
                context, PushIdentity(appPrefs, context), pushApi, tokens, appPrefs, uiLanguage, idleScope, fixtures,
                DepartingDeviceUnregister(OwedUnregister(credentials), pushApi, tokens, appPrefs),
            ),
            notificationSettingsSync = NotificationSettingsSync(
                SettingsDocumentApiClient(refusing, appPrefs, tokens), liveUpdate, appPrefs, tokens, credentials,
                assignmentReminders, dataCache, idleScope,
            ),
            authTokenManager = tokens,
            moodleTokenService = moodleTokens,
            storedPasswordLatch = latch,
            courseService = courseService,
            moodleService = MoodleService(
                sessionManager, moodleTokens, courseService, dataCache, appPrefs,
                MoodleWebservice(sessionManager, moodleTokens), idleScope,
            ),
            dataCache = dataCache,
            bulletinCache = bulletinCache,
            bulletinReadStateStore = BulletinReadStateStore(context),
            importedClassTables = imported,
            widgetUpdater = WidgetUpdater(
                context, dataCache, WidgetBoundaryScheduler(context, academicCalendar),
                AnalyticsLogger(context, appPrefs), idleScope, uiLanguage,
            ),
            appScope = signOutScope,
            demoAccount = demoAccount,
            prefs = appPrefs,
            classroomUsage = ClassroomUsageService(sessionManager, sso, ClassroomDemoData(context, demoAccount, appPrefs)),
            heldShareLink = HeldShareLink(),
        )
        // The in-app browser's WebView storage, counted each time it is cleared.
        val browserClearings = MutableStateFlow(0)
        val liveUpdates = LiveActivityManager(
            preferences = liveUpdate,
            notifier = LiveActivityNotifier(context, liveUpdate, appPrefs, NotificationChannelRegistrar(context, appPrefs)),
            dataCache = dataCache,
            isSignedIn = { auth.authState.value },
            appPrefs = appPrefs,
            classPreparingScheduler = ClassPreparingNotificationScheduler(context),
            academicCalendar = academicCalendar,
            boundaryScheduler = LiveActivityBoundaryScheduler(context),
            appScope = idleScope,
        )
        val browser = BrowserDataCleaner(
            storage = {
                browserClearings.value++
                StorageClearing(inTime = true)
            },
            settings = BrowserSettings(context),
            downloads = BrowserDownloads(idleScope) { _, _ -> throw IOException("no downloads in this test") },
            cleanup = BrowserCleanup(context),
            scope = idleScope,
        )
        AccountSessionEffects(
            context = context,
            accountState = auth.accountState,
            signIns = auth.signIns,
            assignmentReminders = assignmentReminders,
            refreshLiveUpdates = liveUpdates::refresh,
            stopLiveUpdates = liveUpdates::stop,
            clearBrowser = browser::clear,
            browserClearingOwed = { false },
        ).start(effectsScope)

        // What the first student's sessions kept besides: DataCache through its own saves, a
        // bulletin snapshot and an imported class table.
        dataCache.saveCourses(listOf(Course(courseNo = "CS1001", courseName = "Calculus")), SEMESTER)
        dataCache.saveAssignments(listOf(Assignment("1", "CS1001", "Calculus", "Homework 1", Date())))
        dataCache.saveMoodleData(listOf("forum"), System.currentTimeMillis(), "forums-7", FIRST)
        bulletinCache.save(emptyList())
        bulletinCache.saveFetchedAt(System.currentTimeMillis())
        val classmate = ClassTableShareCode(
            ClassTableShareCode.Format.TIGER_DUCK, CLASSMATE, null, listOf(ClassTableShareCode.Term(SEMESTER, listOf("CS1001"))),
        )
        assertNotNull(imported.add(classmate, "Ming", emptyMap()))
        val bulletins = File(context.filesDir, "bulletins")
        // Writes a process death cut short.
        val importedUnfinished = File(ImportedClassTableStore.fileIn(context).path + ".tmp").apply { writeText("{}") }
        File(bulletins, "summaries.json.tmp").writeText("[]")
        File(bulletins, "fetched_at.tmp").writeText("1")
        assertTrue(filesUnder(bulletins).isNotEmpty())

        /** Nothing of the first student's is left, and their School Mail and library account stay. */
        suspend fun assertNothingOfTheFirstLeft(after: String) {
            // Gone, or back to a value of no one's: none still holds what the first student left.
            val left = firstLeft.filter { (at, value) -> prefs(at.first).all[at.second] == value }.keys
            assertEquals(after, emptySet<Pair<String, String>>(), left)
            assertEquals(after, emptyList<String>(), listOf("cache", "data").flatMap { filesUnder(cacheRoots.getValue(it)) })
            assertEquals(after, emptyList<String>(), filesUnder(dataCache.moodleDownloadsRoot()))
            assertEquals(after, emptyList<String>(), filesUnder(bulletins))
            assertEquals(after, emptyList<String>(), imported.load().people.map { it.nickname })
            assertFalse(after, importedUnfinished.exists())
            assertEquals(after, othersBefore, credentialPrefs.all.filterKeys { it.startsWith("mail_") || it.startsWith("library_") })
        }

        auth.logout()
        withTimeout(10_000) {
            signOutJob.children.toList().joinAll()
            browserClearings.first { it >= 1 }
        }
        // By the sign-out itself: the next sign-in clears some of the same again.
        assertNothingOfTheFirstLeft(after = "the sign-out")
        assertEquals(null, auth.storedStudentId)

        assertTrue(auth.login(SECOND, "second-password"))
        withTimeout(10_000) {
            // The browser's clearing that follows the sign-in.
            browserClearings.first { it >= 2 }
        }

        assertTrue(auth.authState.value)
        assertEquals(SECOND, auth.storedStudentId)
        assertEquals("second-password", credentials.ntustPassword)
        assertNothingOfTheFirstLeft(after = "the second sign-in")
    }

    private companion object {
        const val FIRST = "B00015998"
        const val SECOND = "B00015999"
        const val CLASSMATE = "B00015997"
        const val SEMESTER = "1141"
        const val SERVICE = "https://courseselection.ntust.edu.tw/"
        const val LOGIN_URL = "https://ssoam2.ntust.edu.tw/account/login?ReturnUrl=%2Fconnect"
        const val SERVICE_PAGE = "<html><body>Course selection</body></html>"

        val LOGIN_PAGE = """
            <html><body>
            <form id="loginForm" action="/account/login" method="post">
              <input type="hidden" name="__RequestVerificationToken" value="made-up-token" />
              <input type="text" name="Username" />
              <input type="password" name="Password" />
            </form>
            </body></html>
        """.trimIndent()
    }
}
