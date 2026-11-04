package org.ntust.app.tigerduck.data

import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.ntust.app.tigerduck.push.CourseUploadAnswer
import org.ntust.app.tigerduck.push.CourseUploadListener
import org.ntust.app.tigerduck.shared.account.SignIn
import org.ntust.app.tigerduck.sync.outbox.Outbox
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What every course upload that landed tells the outbox, whichever path sent it: the courses it
 * took and the ones it left out as tombstoned, to the course colors and names
 * ([PendingCourseOverrides.courseUploadAnswered]), then that it landed
 * ([Outbox.courseUploadLanded]), so an edit parked until the backend had its course goes again.
 * A hand-added course the upload could not name ([CourseUploadAnswer.leftOut]) is kept as the
 * oldest add of the adds channel ([PendingCourseAdds.queueAsOldest]), which sends it at the next
 * sync. Under the upload's own sign-in. Each lazily, as each sends through the client that tells
 * this.
 */
@Singleton
class CourseUploadNews @Inject constructor(
    private val overrides: Lazy<PendingCourseOverrides>,
    private val outbox: Lazy<Outbox>,
    // The adds channel, which keeps an add of each hand-added course an upload left out; none in a test that is not about it.
    private val adds: Lazy<PendingCourseAdds>? = null,
) : CourseUploadListener {
    override suspend fun landed(semester: String, sent: List<String>, answer: CourseUploadAnswer, signIn: SignIn) {
        overrides.get().courseUploadAnswered(semester, answer.accepted, answer.skippedOf(sent), signIn)
        // Left out past the fifty an upload names, as the oldest add: it fills only what the backend holds no stamp for.
        answer.leftOut.forEach { courseNo -> adds?.get()?.queueAsOldest(semester, courseNo, signIn) }
        outbox.get().courseUploadLanded(signIn)
    }
}

/** The client's course uploads are told to the outbox. */
@Module
@InstallIn(SingletonComponent::class)
abstract class CourseUploadNewsModule {
    @Binds
    abstract fun listener(news: CourseUploadNews): CourseUploadListener
}
