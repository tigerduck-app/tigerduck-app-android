package org.ntust.app.tigerduck.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.mail.AssetsMailDemoGate
import org.ntust.app.tigerduck.mail.MailDemoGate
import org.ntust.app.tigerduck.mail.MailDevServerStore
import org.ntust.app.tigerduck.mail.MailRepository
import org.ntust.app.tigerduck.mail.MailServerConfigSource
import org.ntust.app.tigerduck.mail.MailSite
import org.ntust.app.tigerduck.mail.SchoolMailRepository
import org.ntust.app.tigerduck.mail.SharedPrefsMailDevServerStore
import org.ntust.app.tigerduck.mail.compose.MessageBuilder
import org.ntust.app.tigerduck.mail.imap.AngusMailSessionFactory
import org.ntust.app.tigerduck.mail.imap.MailSessionFactory
import org.ntust.app.tigerduck.mail.notify.AndroidMailNotifier
import org.ntust.app.tigerduck.mail.notify.MailNotifier
import org.ntust.app.tigerduck.mail.smtp.AngusMailTransport
import org.ntust.app.tigerduck.mail.smtp.MailSender
import org.ntust.app.tigerduck.mail.smtp.MailTransport
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.mail.store.MailCredentialStore
import org.ntust.app.tigerduck.mail.store.MailStateStore
import org.ntust.app.tigerduck.mail.store.SharedPrefsMailStateStore
import org.ntust.app.tigerduck.mail.sync.ExactAlarmAccess
import org.ntust.app.tigerduck.mail.sync.MailAlarmScheduler
import org.ntust.app.tigerduck.mail.sync.MailBackgroundScheduler
import org.ntust.app.tigerduck.mail.sync.MailClock
import org.ntust.app.tigerduck.ui.screen.mail.ContentResolverAttachmentReader
import org.ntust.app.tigerduck.ui.screen.mail.PickedAttachmentReader
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MailModule {
    /**
     * `BuildConfig.DEBUG` is a compile-time constant, so a release build never constructs
     * the preferences-backed store: R8 drops it, and with it the name of the file it would
     * have read. The override is absent from a release APK, not hidden inside one.
     */
    @Provides
    @Singleton
    fun devServerStore(@ApplicationContext context: Context): MailDevServerStore =
        if (BuildConfig.DEBUG) SharedPrefsMailDevServerStore(context) else MailDevServerStore.None

    /**
     * Resolved per connection rather than provided as a value: [MailSite] can answer
     * differently after the debug override changes, and both consumers below are singletons.
     * A release build has nothing that could answer anything but `MailServerConfig.NTUST`.
     */
    @Provides
    @Singleton
    fun serverConfigs(site: MailSite): MailServerConfigSource = MailServerConfigSource { site.config() }

    @Provides
    @Singleton
    fun sessionFactory(configs: MailServerConfigSource): MailSessionFactory = AngusMailSessionFactory(configs)

    @Provides
    @Singleton
    fun transport(configs: MailServerConfigSource): MailTransport = AngusMailTransport(configs)

    @Provides
    @Singleton
    fun messageBuilder(): MessageBuilder = MessageBuilder()

    @Provides
    @Singleton
    fun sender(builder: MessageBuilder, transport: MailTransport, sessions: MailSessionFactory): MailSender =
        MailSender(builder, transport, sessions)

    @Provides
    @Singleton
    fun cache(@ApplicationContext context: Context): MailCache = MailCache(File(context.cacheDir, "mail"))

    @Provides
    @Singleton
    fun state(@ApplicationContext context: Context): MailStateStore = SharedPrefsMailStateStore(context)

    @Provides
    fun clock(): MailClock = MailClock { System.currentTimeMillis() }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MailBindings {
    @Binds
    abstract fun credentials(impl: CredentialManager): MailCredentialStore

    @Binds
    abstract fun repository(impl: MailRepository): SchoolMailRepository

    @Binds
    abstract fun notifier(impl: AndroidMailNotifier): MailNotifier

    @Binds
    abstract fun scheduler(impl: MailAlarmScheduler): MailBackgroundScheduler

    @Binds
    abstract fun exactAlarms(impl: MailAlarmScheduler): ExactAlarmAccess

    @Binds
    abstract fun demo(impl: AssetsMailDemoGate): MailDemoGate

    @Binds
    abstract fun pickedAttachmentReader(impl: ContentResolverAttachmentReader): PickedAttachmentReader
}
