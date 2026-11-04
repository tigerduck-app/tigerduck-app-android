package org.ntust.app.tigerduck.sync

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.ntust.app.tigerduck.auth.AccountWorld
import org.ntust.app.tigerduck.data.CourseIgnoreSync
import org.ntust.app.tigerduck.data.CourseUploadNews
import org.ntust.app.tigerduck.data.PendingCourseAdds
import org.ntust.app.tigerduck.data.PendingCourseDeletes
import org.ntust.app.tigerduck.data.PendingCourseOverrides
import org.ntust.app.tigerduck.data.PendingHolidayOverrides
import org.ntust.app.tigerduck.data.UnsentMarks
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.ApiVersionInterceptor
import org.ntust.app.tigerduck.network.SyncClockInterceptor
import org.ntust.app.tigerduck.notification.AssignmentNotificationScheduler
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.push.SyncApiClient
import org.ntust.app.tigerduck.sync.outbox.OldEntryTime
import org.ntust.app.tigerduck.sync.outbox.Outbox
import org.ntust.app.tigerduck.sync.reconcile.FirstSyncReconciler
import org.ntust.app.tigerduck.sync.reconcile.HolidaysReconciler
import org.ntust.app.tigerduck.sync.reconcile.IgnoresReconciler
import org.ntust.app.tigerduck.sync.reconcile.MarksReconciler
import org.ntust.app.tigerduck.sync.reconcile.OverridesReconciler
import org.ntust.app.tigerduck.sync.reconcile.RosterReconciler
import org.ntust.app.tigerduck.ui.screen.home.HomeBackendSync
import org.ntust.app.tigerduck.ui.screen.home.HomeSyncRig
import org.ntust.app.tigerduck.ui.screen.home.idleTermSync
import org.ntust.app.tigerduck.ui.screen.settings.ReenableCheck
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The backend's client as the app builds it (`NetworkModule`): the version latch and the clock
 * reader on the shared client, then [rules], then [backend], which answers in the backend's place.
 */
internal fun appBackendClient(prefs: AppPreferences, clock: SyncClock, backend: OkHttpClient, rules: Interceptor): OkHttpClient =
    backend.newBuilder().apply {
        interceptors().addAll(0, listOf(ApiVersionInterceptor(prefs), SyncClockInterceptor(prefs, clock), rules))
    }.build()

/**
 * `/v4`'s rules for a write, ahead of the backend's stand-in (`docs/api-v4.md`, "Mapping" and
 * "Course upload"): a write that takes `hlc` and comes without one is refused with 422
 * `hlc_required`, as is an add without one, and an upload that carries `force_keys` or
 * `course_overrides` with 422 `invalid_request`. Each refusal is kept in [refused]; every other
 * request goes on as it came.
 */
internal class V4WriteRules : Interceptor {
    val refused = CopyOnWriteArrayList<String>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath
        val sent = request.body?.let { Buffer().also(it::writeTo).readUtf8() }
        val body = sent?.takeIf { it.isNotEmpty() }?.let(::JSONObject)
        val stamped = when (request.method) {
            "DELETE" -> path.startsWith("/v4/sync/courses")
            "PATCH" -> Regex("^/v4/sync/(assignments|courses)/[^/]+/override$").matches(path)
            "PUT" -> path.startsWith("/v4/sync/holiday-overrides/")
            else -> false
        }
        val code = when {
            stamped && request.url.queryParameter("hlc") == null && body?.has("hlc") != true -> "hlc_required"
            path == "/v4/sync/courses/upload" && body != null && (body.has("force_keys") || body.has("course_overrides")) ->
                "invalid_request"
            path == "/v4/sync/courses/upload" && body?.optJSONArray("adds")?.let { adds ->
                (0 until adds.length()).any { !adds.getJSONObject(it).has("hlc") }
            } == true -> "hlc_required"
            else -> null
        }
        if (code == null) {
            // The body read here, sent again as it was, so the stand-in reads it too.
            val again = sent?.let { request.newBuilder().method(request.method, it.toRequestBody(request.body?.contentType())).build() }
            return chain.proceed(again ?: request)
        }
        refused += "${request.method} $path: $code"
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(422).message("")
            .body("""{"error":"$code","message":"refused by the test's /v4 rules"}""".toResponseBody("application/json".toMediaType()))
            .build()
    }
}

/** What [appSyncRig] builds beyond Home's sync: the outbox and the clients, with the app's own wiring. */
internal class AppSyncRig(
    val home: HomeSyncRig,
    val outbox: Outbox,
    val pushApi: PushApiClient,
    val syncApi: SyncApiClient,
)

/**
 * Home's full sync over [w]'s stores as Hilt builds it, where [org.ntust.app.tigerduck.ui.screen.home.homeSyncRig]
 * leaves parts out: every `/v4` write stamped by one [SyncClock] that hears each answer, each queue
 * given [oldEntryTime] (X1), one [AckClock], the course uploads told to the outbox, the catalogue
 * for an older build's course change, and the first sync among the reconcilers. Its requests go through [client] ([appBackendClient]); the token
 * manager's are [w]'s. What would run past the sync on the application scope goes to [idleScope];
 * the sends the queues run on it go to [appScope].
 */
internal fun appSyncRig(
    context: Context,
    w: AccountWorld,
    client: OkHttpClient,
    clock: SyncClock,
    appScope: CoroutineScope,
    idleScope: CoroutineScope,
    oldEntryTime: OldEntryTime,
): AppSyncRig {
    val policies = SyncPolicies(w.appPrefs)
    val ack = AckClock()
    lateinit var overrides: PendingCourseOverrides
    lateinit var outbox: Outbox
    lateinit var adds: PendingCourseAdds
    val pushApi = PushApiClient(
        client, w.appPrefs, w.tokens, clock,
        dagger.Lazy { CourseUploadNews(dagger.Lazy { overrides }, dagger.Lazy { outbox }, dagger.Lazy { adds }) },
    )
    val syncApi = SyncApiClient(w.tokens, w.appPrefs, client, clock)
    val marks = UnsentMarks(w.dataCache, pushApi, w.appPrefs, policies, clock, ack, appScope, oldEntryTime)
    adds = PendingCourseAdds(w.dataCache, pushApi, policies, ack, appScope, oldEntryTime)
    val deletes = PendingCourseDeletes(w.dataCache, adds, pushApi, clock, policies, ack, appScope)
    overrides = PendingCourseOverrides(w.dataCache, pushApi, w.appPrefs, policies, clock, ack, appScope, oldEntryTime, w.semesterCatalog)
    val holidays = PendingHolidayOverrides(w.appPrefs, pushApi, policies, clock, ack, appScope, oldEntryTime, w.academicCalendar)
    val ignores = CourseIgnoreSync(
        w.dataCache, pushApi, w.appPrefs, AssignmentNotificationScheduler(context), policies, clock, ack, appScope, oldEntryTime,
    )
    outbox = Outbox(setOf(ignores.channel, marks, adds, deletes, overrides, holidays))
    val gate = FirstPullGate(w.sessions, idleScope)
    val marksReconciler = MarksReconciler(w.appPrefs, w.dataCache)
    val overridesReconciler = OverridesReconciler(w.dataCache, w.widgetUpdater, overrides, policies, w.appPrefs)
    val synchronizer = Synchronizer(
        prefs = w.appPrefs,
        syncPolicies = policies,
        dataCache = w.dataCache,
        authTokenManager = w.tokens,
        authService = w.auth,
        syncApiClient = syncApi,
        outbox = outbox,
        pendingCourseAdds = adds,
        pendingCourseDeletes = deletes,
        firstPullGate = gate,
        widgetUpdater = w.widgetUpdater,
        reenableCheck = ReenableCheck(w.appPrefs, syncApi, w.dataCache, w.semesterCatalog, ignores, w.auth, w.sessions, idleScope, context),
        notificationSettingsSync = w.notificationSettingsSync,
        appScope = idleScope,
        marks = marksReconciler,
        ignores = IgnoresReconciler(ignores),
        overrides = overridesReconciler,
        holidays = HolidaysReconciler(holidays, w.academicCalendar),
        roster = RosterReconciler(
            w.dataCache, w.auth, pushApi, w.courseService, w.semesterCatalog, w.widgetUpdater, adds, deletes, overrides,
            idleTermSync(w), idleScope, policies,
        ),
        ack = ack,
        firstSync = FirstSyncReconciler(w.dataCache, marks, ignores, overrides, deletes),
    )
    val sync = HomeBackendSync(synchronizer, w.auth, pushApi, w.notificationSettingsSync)
    val home = HomeSyncRig(syncApi, marks, adds, deletes, overrides, holidays, ignores, gate, synchronizer, sync)
    return AppSyncRig(home, outbox, pushApi, syncApi)
}

/** An [OldEntryTime] whose first run is [firstRunMs], recorded as the process starts, and whose clock stays there. */
internal fun oldEntryTimeAt(context: Context, firstRunMs: Long): OldEntryTime =
    OldEntryTime(context.getSharedPreferences("tigerduck_outbox", Context.MODE_PRIVATE), Dispatchers.IO) { firstRunMs }
        .apply { recordFirstRun() }
