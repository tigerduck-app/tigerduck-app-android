package org.ntust.app.tigerduck.network

/**
 * Where the tokens a Moodle sign-in harvests are kept: the wstoken every call takes, and the
 * private token beside it, which only ever asks Moodle for an autologin key. Both are secrets:
 * never logged, and the private token goes nowhere but that request to the Moodle site.
 */
internal interface MoodleTokenPairStore {
    var token: String?
    var privateToken: String?
    val studentId: String?
    val password: String?

    /**
     * Stores the pair only while [studentId] is still the stored account, as one step with a
     * sign-out's clear, so a sign-out can never land between the check and the store.
     */
    fun storeIfSignedIn(studentId: String, token: String, privateToken: String?): Boolean
}

/**
 * The coordinator's token store over [store], which keeps each private token with the wstoken it
 * was harvested with: a renewed token comes with its own, and one harvested without any clears
 * the last, as iOS does. [harvested] is told of each sign-in's pair before the coordinator stores
 * its wstoken.
 */
internal class MoodleTokenPairs(private val store: MoodleTokenPairStore) : MoodleTokenCoordinator.Tokens {

    private var lastHarvest: Pair<String, String?>? = null

    @Synchronized
    fun harvested(token: String, privateToken: String?) {
        lastHarvest = token to privateToken
    }

    @Synchronized
    private fun privateTokenFor(token: String): String? = lastHarvest?.takeIf { it.first == token }?.second

    override var token: String?
        get() = store.token
        set(value) {
            store.token = value
            store.privateToken = value?.let(::privateTokenFor)
        }

    /** The private token stored, whichever wstoken it came with. */
    val privateToken: String? get() = store.privateToken

    override val studentId: String? get() = store.studentId
    override val password: String? get() = store.password

    override fun storeIfSignedIn(studentId: String, token: String): Boolean =
        store.storeIfSignedIn(studentId, token, privateTokenFor(token))
}
