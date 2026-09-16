package org.ntust.app.tigerduck.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.mail.AssetsMailDemoGate
import org.ntust.app.tigerduck.mail.MailDemoGate
import org.ntust.app.tigerduck.mail.MailRepository
import org.ntust.app.tigerduck.mail.MailServerConfig
import org.ntust.app.tigerduck.mail.SchoolMailRepository
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
import org.ntust.app.tigerduck.mail.sync.MailAlarmScheduler
import org.ntust.app.tigerduck.mail.sync.MailBackgroundScheduler
import org.ntust.app.tigerduck.mail.sync.MailClock
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MailModule {
    @Provides
    fun serverConfig(): MailServerConfig = MailServerConfig.NTUST

    @Provides
    @Singleton
    fun sessionFactory(config: MailServerConfig): MailSessionFactory = AngusMailSessionFactory(config)

    @Provides
    @Singleton
    fun transport(config: MailServerConfig): MailTransport = AngusMailTransport(config)

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
    fun credentials(manager: CredentialManager): MailCredentialStore = manager

    @Provides
    fun clock(): MailClock = MailClock { System.currentTimeMillis() }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MailBindings {
    @Binds
    abstract fun repository(impl: MailRepository): SchoolMailRepository

    @Binds
    abstract fun notifier(impl: AndroidMailNotifier): MailNotifier

    @Binds
    abstract fun scheduler(impl: MailAlarmScheduler): MailBackgroundScheduler

    @Binds
    abstract fun demo(impl: AssetsMailDemoGate): MailDemoGate
}
