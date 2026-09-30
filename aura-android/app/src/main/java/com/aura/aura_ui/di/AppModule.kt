package com.aura.aura_ui.di

import android.content.Context
import androidx.room.Room
import com.aura.aura_ui.data.database.AuraDatabase
import com.aura.aura_ui.data.repository.AssistantRepository
import com.aura.aura_ui.data.repository.AssistantRepositoryImpl
import com.aura.aura_ui.data.repository.AudioRepository
import com.aura.aura_ui.data.repository.AudioRepositoryImpl
import com.aura.aura_ui.data.repository.SettingsRepository
import com.aura.aura_ui.data.repository.SettingsRepositoryImpl
import com.aura.aura_ui.utils.AndroidLogger
import com.aura.aura_ui.utils.Logger
import com.aura.aura_ui.utils.PermissionManager
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.aura.aura_ui.network.ConnectionManager
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

/**
 * Hilt module that provides application-level dependencies.
 * This module is installed in the SingletonComponent and follows
 * AURA's modular architecture principles.
 *
 * Network Configuration:
 * - The on-device MCP server is canonical; no network backend is required.
 * - The legacy Python backend is opt-in and its URL comes from user settings
 *   (`server_url`), falling back to BuildConfig.DEFAULT_SERVER_URL, which is
 *   empty unless a developer sets `aura.defaultServerUrl` in local.properties.
 * - Never hardcode a LAN/Tailscale address here: it ships to every user.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAuraDatabase(@ApplicationContext context: Context): AuraDatabase {
        return Room.databaseBuilder(
            context,
            AuraDatabase::class.java,
            "aura_database"
        ).build()
    }

    @Provides
    @Singleton
    fun provideSettingsRepository(
        @ApplicationContext context: Context,
    ): SettingsRepository {
        return SettingsRepositoryImpl(context)
    }

    @Provides
    @Singleton
    fun provideAudioRepository(database: AuraDatabase): AudioRepository {
        return AudioRepositoryImpl(database.audioDao())
    }

    // Contacts stack (ContactResolver / ContactSyncService) is @Inject-constructed;
    // these DAO bindings were missing, which is why that stack was never injectable
    // (and thus sat dead) until the resolve_contact MCP tool became its first consumer.
    @Provides
    fun provideAuraContactDao(database: AuraDatabase): com.aura.aura_ui.data.database.dao.AuraContactDao =
        database.contactDao()

    @Provides
    fun provideSttCorrectionDao(database: AuraDatabase): com.aura.aura_ui.data.database.dao.SttCorrectionDao =
        database.sttCorrectionDao()

    @Provides
    @Singleton
    fun provideLogger(): Logger {
        return AndroidLogger()
    }

    @Provides
    @Singleton
    fun providePermissionManager(logger: Logger): PermissionManager {
        return PermissionManager(logger)
    }

    @Provides
    @Singleton
    fun provideGson(): Gson {
        return Gson()
    }

    /**
     * Short-timeout HTTP client for health checks and configuration endpoints.
     * Keeps quick calls from blocking on an unreachable server.
     */
    @Provides
    @Singleton
    @Named("health")
    fun provideHealthOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    /**
     * Long-timeout HTTP client for AURA task execution.
     *
     * AURA tasks run LLM planning + multi-step gestures and routinely take
     * 60–120 s end-to-end. The readTimeout must be longer than the worst-case
     * task duration; 180 s is conservative headroom without waiting forever.
     * connectTimeout stays short — if the server isn't reachable in 10 s it
     * genuinely isn't available.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.HEADERS // BODY is too verbose for large screenshots
        }
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)  // AURA tasks can take 60-120 s
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(loggingInterceptor)
            .build()
    }

    @Provides
    @Singleton
    fun provideConnectionManager(): ConnectionManager = ConnectionManager()

    /**
     * Provides Retrofit instance with dynamic network configuration.
     * Uses intelligent network detection for development flexibility.
     */
    @Provides
    @Singleton
    fun provideAssistantRepository(
        connectionManager: ConnectionManager,
        logger: Logger,
    ): AssistantRepository {
        return AssistantRepositoryImpl(connectionManager, logger)
    }

}
