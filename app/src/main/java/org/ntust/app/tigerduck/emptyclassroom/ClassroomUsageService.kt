package org.ntust.app.tigerduck.emptyclassroom

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.ntust.app.tigerduck.network.NtustSessionManager
import org.ntust.app.tigerduck.network.SsoLoginService
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.util.logSummary
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class ClassroomUsageError : Exception() {
    /** A page without the room grid, even after signing in again. */
    class Unreadable : ClassroomUsageError()

    /** The site's calendar offers no link to the date. */
    class DateUnavailable : ClassroomUsageError()

    /** The sign-in did not get past the school's SSO. */
    class NotSignedIn : ClassroomUsageError()

    /** The site answered with a server error, which a new sign-in would not mend. */
    class ServerError(val code: Int) : ClassroomUsageError()

    /** A sign-out happened while the read waited or ran; what it read belongs to no one now. */
    class SignedOut : ClassroomUsageError()
}

/** The requests the client makes to cour01, apart so the tests can answer them. */
interface Cour01Transport {
    /**
     * The page at the end of the request, or null when it ended on another host, such as the SSO
     * sign-in a lapsed session is sent to. Throws [ClassroomUsageError.ServerError] for a 5xx.
     */
    suspend fun get(url: String): String?

    suspend fun post(url: String, fields: List<Pair<String, String>>): String?
}

/** The main campus's rooms for a day, and when they were read. */
data class KeptUsage(val usage: CampusUsage, val fetchedAtMs: Long)

/**
 * The main campus's room use for a date, from the room booking system (cour01), an ASP.NET
 * WebForms site: every postback sends back the form state of the page before it, so requests run
 * one at a time. Each read starts from a new campus page and keeps no page after it ends: the
 * form state encodes the grid, bookers' names included, and a page left over would also post the
 * date it already shows, which the site's calendar may answer from that state (tigerduck-app#513).
 *
 * Days are kept in memory only, today's fresh for 15 minutes and later ones for 3 hours, and shown
 * while they reload for up to 12 hours; a sign-out drops them.
 */
@Singleton
class ClassroomUsageService internal constructor(
    private val transport: Cour01Transport,
    /** Signs in; asks the check it is given, once it may sign in, whether the read still wants it. */
    private val signIn: suspend (Credentials, isStillWanted: () -> Boolean) -> Boolean,
    private val dropSessionCookies: () -> Unit,
    private val demo: (LocalDate) -> CampusUsage?,
    private val nowMs: () -> Long,
    /** Where a page is parsed: off the main thread, as the whole-campus pages run to hundreds of kilobytes. */
    private val parsing: CoroutineDispatcher = Dispatchers.Default,
) {
    @Inject
    constructor(
        sessionManager: NtustSessionManager,
        ssoLoginService: SsoLoginService,
        demoData: ClassroomDemoData,
    ) : this(
        transport = OkHttpCour01Transport({ sessionManager.client }),
        signIn = { credentials, isStillWanted ->
            ssoLoginService.ensureServiceLogin(QUERY_URL, credentials.studentId, credentials.password, isStillWanted)
        },
        dropSessionCookies = { sessionManager.dropCookies(HOST) },
        demo = demoData::usage,
        nowMs = AppClock::nowMillis,
    )

    data class Credentials(val studentId: String, val password: String) {
        override fun toString(): String = "Credentials(***)"
    }

    private val kept = ConcurrentHashMap<Int, KeptUsage>()

    /** Moved by [forget], so a read running across a sign-out writes nothing back. */
    private val epoch = AtomicInteger()
    private val oneAtATime = Mutex()

    /**
     * The day as last read, to show while it refreshes; null once it is too old to show as current.
     * The demo account's day is always at hand, so a visit shows it at once instead of loading.
     */
    fun keptUsage(day: LocalDate): KeptUsage? {
        demo(day)?.let { return KeptUsage(it, nowMs()) }
        return kept[ClassroomUsageParser.dayNumber(day)]?.takeIf { nowMs() - it.fetchedAtMs < STALE_LIMIT_MS }
    }

    /** [isToday] sets only how long the day stays fresh: today's date is posted like any other. */
    suspend fun usage(day: LocalDate, isToday: Boolean, force: Boolean, credentials: Credentials): KeptUsage {
        demo(day)?.let { return KeptUsage(it, nowMs()) }
        val number = ClassroomUsageParser.dayNumber(day)
        val ticket = epoch.get()
        if (!force) fresh(number, isToday)?.let { return it }
        return oneAtATime.withLock {
            // A sign-out while this read waited ends it before any request; a read that waited
            // may also find the day the one before it read.
            check(ticket)
            if (!force) fresh(number, isToday)?.let { return@withLock it }
            val usage = fetch(number, credentials, ticket)
            check(ticket)
            KeptUsage(usage, nowMs()).also { kept[number] = it }
        }
    }

    /** Sign-out calls this. The cour01 cookies go when the sign-out empties the cookie jar. */
    fun forget() {
        epoch.incrementAndGet()
        kept.clear()
    }

    private fun check(ticket: Int) {
        if (ticket != epoch.get()) throw ClassroomUsageError.SignedOut()
    }

    private fun fresh(day: Int, isToday: Boolean): KeptUsage? {
        val entry = kept[day] ?: return null
        val lifetime = if (isToday) TODAY_LIFETIME_MS else LATER_LIFETIME_MS
        return entry.takeIf { nowMs() - it.fetchedAtMs < lifetime }
    }

    /**
     * A page without the grid means the cour01 session lapsed, so the read starts over once from
     * a new sign-in, without the cour01 cookies. A server error is no lapsed session and signs in
     * to nothing, but it drops the cour01 cookies too, so a session the site broke on its side
     * does not outlive it: the next read or Retry starts clean.
     */
    private suspend fun fetch(day: Int, credentials: Credentials, ticket: Int): CampusUsage =
        try {
            try {
                read(day, credentials, ticket)
            } catch (_: ClassroomUsageError.Unreadable) {
                check(ticket)
                dropSessionCookies()
                try {
                    read(day, credentials, ticket)
                } catch (e: ClassroomUsageError.Unreadable) {
                    Log.w(TAG, "cour01 gave no room grid after signing in again")
                    throw e
                }
            }
        } catch (e: ClassroomUsageError.ServerError) {
            dropSessionCookies()
            throw e
        }

    private suspend fun read(day: Int, credentials: Credentials, ticket: Int): CampusUsage {
        var page = campusPage(credentials, ticket)
        // The campus page shows a grid as well, but nothing on it is known to name that grid's
        // day, which the site may keep from an earlier read, so today's date is posted too
        // (tigerduck-app#475).
        var monthSteps = 0
        while (true) {
            val step = ClassroomUsageParser.calendarStep(day, page.calendarDays, page.monthSteps)
                ?: throw ClassroomUsageError.DateUnavailable()
            page = post(step, page, ticket = ticket)
            if (!step.argument.startsWith("V")) break
            monthSteps++
            if (monthSteps >= MAX_MONTH_STEPS) throw ClassroomUsageError.DateUnavailable()
        }

        // A postback can answer with a later page first, so pages are read by number. A page read
        // twice, one for another campus or more pages than the cap is no whole day.
        val rooms = sortedMapOf<Int, List<RoomUsage>>()
        while (true) {
            val grid = page.grid
            if (grid == null || page.campus != CAMPUS || grid.page in rooms) {
                throw ClassroomUsageError.Unreadable()
            }
            rooms[grid.page] = grid.rooms
            val next = grid.pageLinks.filterKeys { it !in rooms }.minByOrNull { it.key } ?: break
            if (rooms.size >= MAX_PAGES) throw ClassroomUsageError.Unreadable()
            page = post(next.value, page, ticket = ticket)
        }
        return CampusUsage(rooms.values.flatten(), page.buildingNames)
    }

    /** The query page with the main campus chosen, reached by signing in when the session has lapsed. */
    private suspend fun campusPage(credentials: Credentials, ticket: Int): ClassroomUsageParser.Page {
        var html = transport.get(QUERY_URL)
        if (html == null || !ClassroomUsageParser.isQueryPage(html)) {
            check(ticket)
            // The SSO login waits its turn behind any other; a sign-out meanwhile refuses it there.
            val signedIn = signIn(credentials) { ticket == epoch.get() }
            check(ticket)
            if (!signedIn) throw ClassroomUsageError.NotSignedIn()
            html = transport.get(QUERY_URL)
        }
        if (html == null || !ClassroomUsageParser.isQueryPage(html)) throw ClassroomUsageError.Unreadable()
        val query = parse(html)
        val campus = post(
            ClassroomUsageParser.Postback(ClassroomUsageParser.CAMPUS_FIELD, ""),
            query,
            values = mapOf(ClassroomUsageParser.CAMPUS_FIELD to CAMPUS),
            ticket = ticket,
        )
        // The query page opens on another campus, whose answer would read as a day with no rooms.
        if (campus.campus != CAMPUS) throw ClassroomUsageError.Unreadable()
        return campus
    }

    private suspend fun post(
        postback: ClassroomUsageParser.Postback,
        from: ClassroomUsageParser.Page,
        values: Map<String, String> = emptyMap(),
        ticket: Int,
    ): ClassroomUsageParser.Page {
        check(ticket)
        val overrides = values + mapOf(
            "__EVENTTARGET" to postback.target,
            "__EVENTARGUMENT" to postback.argument,
        )
        val fields = from.fields.map { it.name to (overrides[it.name] ?: it.value) }.toMutableList()
        for ((name, value) in overrides.toSortedMap()) {
            if (fields.none { it.first == name }) fields += name to value
        }
        val html = transport.post(QUERY_URL, fields)
        if (html == null || !ClassroomUsageParser.isQueryPage(html)) throw ClassroomUsageError.Unreadable()
        val next = parse(html)
        check(ticket)
        return next
    }

    private suspend fun parse(html: String): ClassroomUsageParser.Page =
        withContext(parsing) { ClassroomUsageParser.page(html) }

    companion object {
        const val HOST = "cour01.ntust.edu.tw"
        const val QUERY_URL = "https://$HOST/classroom_user/classroom_usecondition.aspx"
        private const val TAG = "ClassroomUsage"
        private const val CAMPUS = "HQ"
        private const val MAX_PAGES = 20
        private const val MAX_MONTH_STEPS = 3

        /** Bookings change during the day; the timetable under them does not. */
        const val TODAY_LIFETIME_MS = 15 * 60 * 1000L
        const val LATER_LIFETIME_MS = 3 * 60 * 60 * 1000L

        /**
         * How long a day is shown while it reloads. Past this it is too old to stand in for the
         * day at all, so a failed reload shows the failure rather than it (tigerduck-app#477).
         */
        const val STALE_LIMIT_MS = 12 * 60 * 60 * 1000L
    }
}

/**
 * cour01 over the NTUST session's client, a call cancelled with the coroutine that waits for it.
 * [host] is the site's; [log] is where a failure is written, for the tests to read.
 */
internal class OkHttpCour01Transport(
    private val client: () -> OkHttpClient,
    private val host: String = ClassroomUsageService.HOST,
    private val log: (String) -> Unit = { message -> Log.w("ClassroomUsage", message) },
) : Cour01Transport {
    override suspend fun get(url: String): String? = load(Request.Builder().url(url).get().build())

    override suspend fun post(url: String, fields: List<Pair<String, String>>): String? {
        val body = FormBody.Builder().apply { fields.forEach { (name, value) -> add(name, value) } }.build()
        return load(Request.Builder().url(url).header("Referer", url).post(body).build())
    }

    private suspend fun load(request: Request): String? = suspendCancellableCoroutine { continuation ->
        val call = client().newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        when {
                            it.request.url.host != host -> null
                            it.code >= 500 -> throw ClassroomUsageError.ServerError(it.code)
                            else -> it.body.string()
                        }
                    }
                }
                result.onSuccess { continuation.resume(it) }
                    .onFailure { e ->
                        // Leaving the screen or signing out cancels the call; that is no failure
                        // (tigerduck-app#514), and a server error is the caller's to report.
                        if (!call.isCanceled() && e !is ClassroomUsageError) log("cour01 body unreadable: ${e.logSummary()}")
                        continuation.resumeWithException(e)
                    }
            }
        })
    }
}
