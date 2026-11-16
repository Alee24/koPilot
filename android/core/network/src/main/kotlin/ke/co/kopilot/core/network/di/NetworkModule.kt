package ke.co.kopilot.core.network.di

import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ke.co.kopilot.core.network.BuildConfig
import ke.co.kopilot.core.network.api.KoPilotApiService
import ke.co.kopilot.core.security.SecureTokenStore
import ke.co.kopilot.core.security.SensitiveLogRedactor
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(tokenStore: SecureTokenStore): OkHttpClient {
        val logging = HttpLoggingInterceptor { message ->
            Log.d("KoPilotHttp", SensitiveLogRedactor.redact(message))
        }.apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        }

        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val reqBuilder = original.newBuilder()
                    .addHeader("X-Device-Id", tokenStore.getOrCreateDeviceId())
                tokenStore.getAccessToken()?.let { token ->
                    reqBuilder.addHeader("Authorization", "Bearer $token")
                }
                val customBase = tokenStore.getBackendBaseUrl(BuildConfig.KOPILOT_API_BASE_URL).toHttpUrlOrNull()
                if (customBase != null && original.url.host != "api.openai.com" && !original.url.host.endsWith("googleapis.com")) {
                    val newUrl = original.url.newBuilder()
                        .scheme(customBase.scheme)
                        .host(customBase.host)
                        .port(customBase.port)
                        .build()
                    reqBuilder.url(newUrl)
                }
                chain.proceed(reqBuilder.build())
            }
            .addInterceptor(logging)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.KOPILOT_API_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideKoPilotApiService(retrofit: Retrofit): KoPilotApiService =
        retrofit.create(KoPilotApiService::class.java)
}
