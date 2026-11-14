package ke.co.kopilot.feature.alerts.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ke.co.kopilot.feature.alerts.AndroidTtsSpeechSink
import ke.co.kopilot.feature.alerts.SpeechSink
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AlertsModule {
    @Binds
    @Singleton
    abstract fun bindSpeechSink(impl: AndroidTtsSpeechSink): SpeechSink
}
