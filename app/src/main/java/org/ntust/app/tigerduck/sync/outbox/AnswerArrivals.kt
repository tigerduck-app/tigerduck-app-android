package org.ntust.app.tigerduck.sync.outbox

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.WorkerThread
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.ntust.app.tigerduck.auth.session.LateAccountScoped
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.DeviceClocks
import org.ntust.app.tigerduck.sync.EditTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When the answer to each field's last send reached this phone, for the per-key rule of `age_ms`:
 * an edit made before that answer arrived, while the earlier send of its field was out, counts its
 * age from the arrival ([ageFrom]). The backend stamps an edit with its own arrival less the age,
 * and the first send's stamp is its arrival less its age, so a quick second edit counted from its
 * own time could land before the first, the first having been longer on the way. Counted from the
 * first's answer, which came after the first arrived, it lands after.
 *
 * By field ([org.ntust.app.tigerduck.sync.SyncField]), as the write named it, a course by the key
 * it went to, whichever sent it: a lane, or a screen straight (Use Local); a course's add and its
 * delete share one. The client keeps and reads them around every write
 * ([org.ntust.app.tigerduck.push.FieldAges]). Kept in `tigerduck_outbox_answers`, the latest
 * [KEPT], so an edit queued before a process death still counts from its first's answer after it:
 * within the boot by the elapsed clock, which the death leaves running. The school account's:
 * written only while the sign-in of the send stands, and forgotten at its sign-out
 * ([forgetAccount]). A stale arrival changes nothing for an edit made after it, which every edit
 * of the next sign-in is.
 */
@Singleton
class AnswerArrivals internal constructor(
    // Null for one a test keeps in memory alone.
    private val prefs: SharedPreferences?,
    private val clocks: DeviceClocks,
) : LateAccountScoped {

    @Inject
    constructor(@ApplicationContext context: Context, clocks: DeviceClocks) :
        this(context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE), clocks)

    private val lock = Any()

    // Under lock: the arrivals by field, oldest first, as the file holds them once read.
    private var known: LinkedHashMap<String, EditTime>? = null

    /**
     * The answer to a write of [fields] under [signIn] arrived now. Nothing once [signIn] has ended.
     * On disk when this returns (`commit()`), before the write's caller goes on, so an edit kept
     * after it never outlives it; so call it off the main thread, as the client's writes run.
     */
    @WorkerThread
    fun arrived(fields: Collection<String>, signIn: SignIn) {
        if (fields.isEmpty()) return
        val at = clocks.now()
        signIn.ifCurrent {
            synchronized(lock) {
                val arrivals = read()
                for (field in fields) {
                    arrivals.remove(field)
                    arrivals[field] = at
                }
                while (arrivals.size > KEPT) arrivals.remove(arrivals.keys.first())
                prefs?.edit(commit = true) { putString(KEY_ARRIVALS, written(arrivals)) }
            }
        }
    }

    /**
     * The moment the age of [field]'s edit made at [madeAt] counts from: the arrival of the answer
     * to an earlier send of the field, when it came after the edit, else [madeAt]. The oldest
     * value ([EditTime.OLDEST]) stays the oldest.
     */
    fun ageFrom(field: String, madeAt: EditTime): EditTime {
        if (madeAt.isOldest) return madeAt
        val arrived = synchronized(lock) { read()[field] } ?: return madeAt
        return if (madeAt.isBefore(arrived)) arrived else madeAt
    }

    /** At the school's sign-out: every arrival, which names the account's courses and assignments. */
    override suspend fun forgetAccount() {
        synchronized(lock) {
            known = LinkedHashMap()
            prefs?.edit { remove(KEY_ARRIVALS) }
        }
    }

    // Under lock.
    private fun read(): LinkedHashMap<String, EditTime> = known ?: parsed(prefs?.getString(KEY_ARRIVALS, null)).also { known = it }

    // Each arrival as [field, wall, elapsed, boot], oldest first; one that does not read is left out.
    private fun parsed(text: String?): LinkedHashMap<String, EditTime> {
        val arrivals = LinkedHashMap<String, EditTime>()
        val rows = text?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return arrivals
        for (i in 0 until rows.length()) {
            val row = rows.optJSONArray(i) ?: continue
            val field = row.optString(0).takeIf { it.isNotEmpty() } ?: continue
            val wall = row.opt(1) as? Number ?: continue
            arrivals[field] = EditTime(wall.toLong(), (row.opt(2) as? Number)?.toLong(), (row.opt(3) as? Number)?.toInt())
        }
        return arrivals
    }

    private fun written(arrivals: Map<String, EditTime>): String = JSONArray().apply {
        for ((field, at) in arrivals) {
            put(JSONArray().put(field).put(at.wallMs).put(at.elapsedMs ?: JSON_NULL).put(at.boot ?: JSON_NULL))
        }
    }.toString()

    companion object {
        /** How many fields' arrivals are kept, the latest. */
        const val KEPT = 200

        private const val PREFS_FILE = "tigerduck_outbox_answers"
        private const val KEY_ARRIVALS = "arrivals"
        private val JSON_NULL: Any = org.json.JSONObject.NULL

        /** Arrivals kept in memory alone: for a queue a test builds without the app's. */
        fun inMemory(clocks: DeviceClocks = DeviceClocks.WALL): AnswerArrivals = AnswerArrivals(null, clocks)
    }
}
