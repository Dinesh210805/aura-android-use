package com.aura.aura_ui.data.repository

import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing user settings.
 */
interface SettingsRepository {
    /**
     * Observes the microphone permission status.
     */
    fun observeMicrophonePermission(): Flow<Boolean>

    /**
     * Updates the microphone permission status.
     */
    suspend fun updateMicrophonePermission(granted: Boolean)

    /**
     * Observes the overlay permission status.
     */
    fun observeOverlayPermission(): Flow<Boolean>

    /**
     * Updates the overlay permission status.
     */
    suspend fun updateOverlayPermission(granted: Boolean)

    /**
     * Observes whether the service is running.
     */
    fun observeServiceRunning(): Flow<Boolean>

    /**
     * Updates the service running status.
     */
    suspend fun updateServiceRunning(running: Boolean)

    /**
     * Observes the custom server URL.
     */
    fun observeServerUrl(): Flow<String?>

    /**
     * Updates the custom server URL.
     */
    suspend fun updateServerUrl(url: String?)

    /**
     * Observes whether to use auto-discovery for server connection.
     */
    fun observeUseAutoDiscovery(): Flow<Boolean>

    /**
     * Updates whether to use auto-discovery for server connection.
     */
    suspend fun updateUseAutoDiscovery(useAutoDiscovery: Boolean)

    /**
     * Observes the list of saved server URLs.
     */
    fun observeServerUrls(): Flow<List<String>>

    /**
     * Gets the list of saved server URLs.
     */
    suspend fun getServerUrls(): List<String>

    /**
     * Saves the complete list of server URLs.
     */
    suspend fun saveServerUrls(urls: List<String>)

    /**
     * Adds a new server URL to the list.
     */
    suspend fun addServerUrl(url: String)

    /**
     * Removes a server URL from the list.
     */
    suspend fun removeServerUrl(url: String)
}
