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
 * Under the upload's own sign-in. Both lazily, as each of them sends through the client that
 * tells this.
 */
@Singleton
class CourseUploadNews @Inject constructor(
    private val overrides: Lazy<PendingCourseOverrides>,
    private val outbox: Lazy<Outbox>,
) : CourseUploadListener {
    override suspend fun landed(semester: String, sent: List<String>, answer: CourseUploadAnswer, signIn: SignIn) {
        overrides.get().courseUploadAnswered(semester, answer.accepted, answer.skippedOf(sent), signIn)
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
