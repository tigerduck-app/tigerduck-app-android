package org.ntust.app.tigerduck.auth

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertNotNull
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.analytics.AnalyticsLogger
import org.ntust.app.tigerduck.auth.session.AccountPlan
import org.ntust.app.tigerduck.auth.session.AccountSessions
import org.ntust.app.tigerduck.auth.session.SchoolSignOut
import org.ntust.app.tigerduck.auth.session.SchoolStores
import org.ntust.app.tigerduck.auth.session.SignOutPlans
import org.ntust.app.tigerduck.auth.session.SignOutOwed
import org.ntust.app.tigerduck.browser.BrowserCleanup
import org.ntust.app.tigerduck.browser.BrowserDataCleaner
import org.ntust.app.tigerduck.browser.BrowserDownloads
import org.ntust.app.tigerduck.browser.BrowserSettings
import org.ntust.app.tigerduck.browser.StorageClearing
import org.ntust.app.tigerduck.data.BulletinReadStateStore
import org.ntust.app.tigerduck.data.cache.BulletinCache
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.data.preferences.UiLanguageMonitor
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
import org.ntust.app.tigerduck.mail.store.SharedPrefsMailStateStore
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.network.MoodleService
import org.ntust.app.tigerduck.network.MoodleTokenPairStore
import org.ntust.app.tigerduck.network.MoodleTokenService
import org.ntust.app.tigerduck.network.NtustSessionManager
import org.ntust.app.tigerduck.network.SemesterCatalog
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
import org.ntust.app.tigerduck.widget.WidgetBoundaryScheduler
import org.ntust.app.tigerduck.widget.WidgetUpdater
import java.io.File
import java.io.IOException

/** What [accountWorld] builds: the stores and services a sign-out and a sign-in run over. */
internal class AccountWorld(
    val appPrefs: AppPreferences,
    val credentials: CredentialManager,
    val dataCache: DataCache,
    val bulletinCache: BulletinCache,
    val imported: ImportedClassTableStore,
    val tokens: AuthTokenManager,
    val auth: AuthService,
    val browserClearings: MutableStateFlow<Int>,
    val sessions: AccountSessions,
    /** Not started: the caller does, on the scope and at the point it wants. */
    val effects: AccountSessionEffects,
    val pushApi: PushApiClient,
    val notificationSettingsSync: NotificationSettingsSync,
    val courseService: CourseService,
    val semesterCatalog: SemesterCatalog,
    val widgetUpdater: WidgetUpdater,
    val academicCalendar: AcademicCalendarStore,
    val demoAccount: DemoAccount,
    val fixtures: DebugFixtureStore,
    /** Every sign-out: the school's real plan; School Mail and the library hold nothing here. */
    val plans: SignOutPlans,
)

/**
 * The real services over the real stores of [context], the way a process builds them. The
 * sign-out's own clearing runs on [appScope]; the registration, the settings pushes and everything
 * else that would reach a network run on [idleScope], which never runs them. The school is a fake,
 * and every other request is refused. Play only: the widgets' analytics logger differs by flavor.
 */
internal fun accountWorld(
    context: Context,
    credentialPrefs: SharedPreferences,
    appScope: CoroutineScope,
    idleScope: CoroutineScope,
    /** The backend's stand-in for the requests a sync sends; refused without one. */
    backend: OkHttpClient? = null,
    /** The backend's stand-in for its logins; refused without one. */
    backendLogin: OkHttpClient? = null,
    /** Runs as the school takes a password, before its answer comes back. */
    schoolAnswering: () -> Unit = {},
): AccountWorld {
    val prefs = { file: String -> context.getSharedPreferences(file, Context.MODE_PRIVATE) }
    val school = OkHttpClient.Builder().addInterceptor { chain ->
        if (chain.request().method == "POST") schoolAnswering()
        schoolPage(chain.request())
    }.build()
    val refusing = OkHttpClient.Builder().addInterceptor { throw IOException("no network in this test") }.build()
    val sessions = AccountSessions(
        DebugFixtureStore(context), appScope, SignOutOwed(prefs("tigerduck_account_wipe")), SharedPrefsMailStateStore(context),
    )
    val appPrefs = AppPreferences(context)
    val credentials = CredentialManager(credentialPrefs)
    val fixtures = DebugFixtureStore(context)
    val academicCalendar = AcademicCalendarStore(context, refusing, appPrefs)
    val dataCache = DataCache(context, academicCalendar)
    val bulletinCache = BulletinCache(context)
    val imported = ImportedClassTableStore({ ImportedClassTableStore.fileIn(context) }, { credentials.ntustStudentId }, Dispatchers.Unconfined)
    val demoAccount = DemoAccount(context, fixtures, dataCache, bulletinCache, appPrefs, imported, sessions)
    val sessionManager = NtustSessionManager(appPrefs, DemoModeInterceptor(fixtures), session = sessions.school)
    val latch = PersistedStoredPasswordLatch(context, credentials)
    val sso = SsoLoginService(sessionManager, latch) { school }
    val tokens = AuthTokenManager(credentials.v3Tokens, backendLogin ?: refusing, appPrefs, "device-1", session = sessions.school)
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
    val courseService = CourseService(context, sessionManager, sso, dataCache, appPrefs, academicCalendar, sessions, idleScope)
    val uiLanguage = UiLanguageMonitor(appPrefs)
    val pushApi = PushApiClient(backend ?: refusing, appPrefs, tokens)
    val liveUpdate = LiveActivityPreferences(context)
    val assignmentReminders = AssignmentNotificationScheduler(context)
    val notificationSettingsSync = NotificationSettingsSync(
        SettingsDocumentApiClient(refusing, appPrefs, tokens), liveUpdate, appPrefs, tokens, credentials,
        assignmentReminders, dataCache, idleScope,
    )
    val widgetUpdater = WidgetUpdater(
        context, dataCache, WidgetBoundaryScheduler(context, academicCalendar),
        AnalyticsLogger(context, appPrefs), idleScope, uiLanguage,
    )
    val pushRegistration = PushRegistrationService(
        context, PushIdentity(appPrefs, context), pushApi, tokens, appPrefs, uiLanguage, idleScope,
        DepartingDeviceUnregister(OwedUnregister(credentials), pushApi, tokens, appPrefs),
    )
    lateinit var plans: SignOutPlans
    val auth = AuthService(
        context = context,
        sessionManager = sessionManager,
        ssoLoginService = sso,
        credentials = credentials,
        pushRegistration = pushRegistration,
        notificationSettingsSync = notificationSettingsSync,
        authTokenManager = tokens,
        moodleTokenService = moodleTokens,
        storedPasswordLatch = latch,
        importedClassTables = imported,
        appScope = appScope,
        demoAccount = demoAccount,
        sessions = sessions,
        signOutPlans = { plans },
    )
    plans = SignOutPlans(
        school = SchoolSignOut(
            context = context,
            auth = auth,
            credentials = credentials,
            authTokenManager = tokens,
            notificationSettingsSync = notificationSettingsSync,
            courseService = courseService,
            moodleService = MoodleService(
                sessionManager, moodleTokens, courseService, dataCache, appPrefs,
                MoodleWebservice(sessionManager, moodleTokens), sessions, idleScope,
            ),
            classroomUsage = ClassroomUsageService(sessionManager, sso, ClassroomDemoData(context, sessions.school, appPrefs)),
            prefs = appPrefs,
            sessionManager = sessionManager,
            stores = SchoolStores({ BulletinReadStateStore(context) }, { appPrefs }, { latch }, { imported }, { dataCache }),
            pushRegistration = pushRegistration,
            demoAccount = demoAccount,
            bulletinCache = bulletinCache,
            widgetUpdater = widgetUpdater,
            session = sessions.school,
            appScope = appScope,
        ),
        mail = AccountPlan({ false }) {},
        library = AccountPlan({ false }) {},
        readLibraryAgain = {},
        leaveDemoLibrary = demoAccount::signOutLibrary,
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
        session = sessions.school,
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
    val effects = AccountSessionEffects(
        context = context,
        events = sessions.events,
        signedIn = { auth.authState.value },
        assignmentReminders = assignmentReminders,
        refreshLiveUpdates = liveUpdates::refresh,
        stopLiveUpdates = liveUpdates::stop,
        clearBrowser = browser::clear,
        browserClearingOwed = { false },
    )
    return AccountWorld(
        appPrefs, credentials, dataCache, bulletinCache, imported, tokens, auth, browserClearings, sessions, effects,
        pushApi, notificationSettingsSync, courseService, SemesterCatalog(sessionManager, appPrefs, credentials), widgetUpdater,
        academicCalendar, demoAccount, fixtures, plans,
    )
}

/** The school's pages: its sign-in form for every visit, and the service once a password is posted. */
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

private const val SERVICE = "https://courseselection.ntust.edu.tw/"
private const val LOGIN_URL = "https://ssoam2.ntust.edu.tw/account/login?ReturnUrl=%2Fconnect"
private const val SERVICE_PAGE = "<html><body>Course selection</body></html>"

private val LOGIN_PAGE = """
    <html><body>
    <form id="loginForm" action="/account/login" method="post">
      <input type="hidden" name="__RequestVerificationToken" value="made-up-token" />
      <input type="text" name="Username" />
      <input type="password" name="Password" />
    </form>
    </body></html>
""".trimIndent()

/**
 * What docs/account-state.md gives the school account, as [student] left it: every key filled,
 * typed as its source reads it, and every DataCache file, in the folder DataCache keeps it in.
 */
internal class SchoolAccountFill(private val context: Context, private val student: String, private val semester: String) {
    private val inventory = AccountStateInventory.app

    private fun prefs(file: String): SharedPreferences = context.getSharedPreferences(file, Context.MODE_PRIVATE)

    /** What the school account keeps under a key: each key of its own, and each of a file of its own. */
    val keys: List<AccountStateInventory.Key> by lazy {
        inventory.rows.filter { it.scope == "school" }.flatMap { row ->
            when (row.store) {
                "prefs" -> inventory.keys[row.name].orEmpty().filter { it.type != null }
                "cache", "database" -> emptyList()
                else -> listOfNotNull(inventory.keys[row.store].orEmpty().firstOrNull { it.name == row.name && it.type != null })
            }
        }
    }

    /** The key [key] is filled under: a family's under a term. */
    private fun stored(key: AccountStateInventory.Key) = key.name.replace("*", semester)

    /** A value of [key]'s type that is not its default. */
    private fun sample(key: AccountStateInventory.Key): Any = when (key.type) {
        "Boolean" -> key.default != "true"
        "Int" -> 7
        "Long" -> 7L
        "Float" -> 1.5f
        "StringSet" -> setOf("1")
        else -> "$student's"
    }

    private fun SharedPreferences.Editor.put(name: String, value: Any): SharedPreferences.Editor = when (value) {
        is Boolean -> putBoolean(name, value)
        is Int -> putInt(name, value)
        is Long -> putLong(name, value)
        is Float -> putFloat(name, value)
        is Set<*> -> putStringSet(name, value.map { it.toString() }.toSet())
        else -> putString(name, value.toString())
    }

    /** Fills every key. */
    fun fillPreferences() {
        keys.groupBy { it.file }.forEach { (file, keys) ->
            prefs(file).edit().apply { keys.forEach { put(stored(it), sample(it)) } }.commit()
        }
    }

    /** What every key holds now, by file and key. */
    fun held(): Map<Pair<String, String>, Any?> = keys.associate { (it.file to stored(it)) to prefs(it.file).all[stored(it)] }

    val cacheRoots by lazy {
        mapOf(
            "cache" to File(context.cacheDir, "TigerDuckCache"),
            "data" to File(context.filesDir, "TigerDuckData"),
            "files" to context.filesDir,
        )
    }

    /**
     * A file of the student's for each DataCache row, where DataCache keeps it: a family's under a
     * term, a folder's with a file in it, and a write cut short beside a file in each of
     * DataCache's folders.
     */
    fun fillDataCache(): List<File> = inventory.rows.filter { it.store == "cache" && it.scope == "school" }.flatMap { row ->
        val where = inventory.cacheRoot(row.name)
        assertNotNull("where DataCache keeps ${row.name}", where)
        val root = cacheRoots.getValue(where!!)
        when {
            row.name == "*.tmp" -> listOf(
                File(cacheRoots.getValue("cache"), "course_lookups.json.tmp"),
                File(cacheRoots.getValue("data"), "unsent_marks.json.tmp"),
            )
            row.name.endsWith("/") -> listOf(File(root, row.name + "7/slides.pdf"))
            else -> listOf(File(root, row.name.replace("*", semester)))
        }.onEach {
            it.parentFile?.mkdirs()
            it.writeText("[]")
        }
    }

    fun filesUnder(root: File): List<String> =
        root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.toList()
}
