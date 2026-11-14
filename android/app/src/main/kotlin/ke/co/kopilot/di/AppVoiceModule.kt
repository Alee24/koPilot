package ke.co.kopilot.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ke.co.kopilot.feature.voice.VoiceToolExecutor
import ke.co.kopilot.voice.KoPilotVoiceToolExecutor
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppVoiceModule {
    @Binds
    @Singleton
    abstract fun bindVoiceToolExecutor(impl: KoPilotVoiceToolExecutor): VoiceToolExecutor
}
