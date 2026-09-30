package com.aura.aura_ui.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import javax.inject.Inject

/**
 * Implementation of SettingsRepository using SharedPreferences.
 */
class SettingsRepositoryImpl
    @Inject
    constructor(
        private val context: Context,
    ) : SettingsRepository {
        private val sharedPreferences: SharedPreferences =
            context.getSharedPreferences("aura_settings", Context.MODE_PRIVATE)

        private val _microphonePermission = MutableStateFlow(false)
        private val _overlayPermission = MutableStateFlow(false)
        private val _serviceRunning = MutableStateFlow(false)
        private val _serverUrl = MutableStateFlow<String?>(null)
        private val _useAutoDiscovery = MutableStateFlow(true)
        private val _serverUrls = MutableStateFlow<List<String>>(emptyList())

        init {
            // Load initial values from SharedPreferences
            _microphonePermission.value = sharedPreferences.getBoolean("microphone_permission", false)
            _overlayPermission.value = sharedPreferences.getBoolean("overlay_permission", false)
            _serviceRunning.value = sharedPreferences.getBoolean("service_running", false)
            _serverUrl.value = sharedPreferences.getString("server_url", null)
            _useAutoDiscovery.value = sharedPreferences.getBoolean("use_auto_discovery", true)
            _serverUrls.value = loadServerUrls()
        }

        override fun observeMicrophonePermission(): Flow<Boolean> = _microphonePermission.asStateFlow()

        override suspend fun updateMicrophonePermission(granted: Boolean) {
            _microphonePermission.value = granted
            sharedPreferences.edit().putBoolean("microphone_permission", granted).apply()
        }

        override fun observeOverlayPermission(): Flow<Boolean> = _overlayPermission.asStateFlow()

        override suspend fun updateOverlayPermission(granted: Boolean) {
            _overlayPermission.value = granted
            sharedPreferences.edit().putBoolean("overlay_permission", granted).apply()
        }

        override fun observeServiceRunning(): Flow<Boolean> = _serviceRunning.asStateFlow()

        override suspend fun updateServiceRunning(running: Boolean) {
            _serviceRunning.value = running
            sharedPreferences.edit().putBoolean("service_running", running).apply()
        }

        override fun observeServerUrl(): Flow<String?> = _serverUrl.asStateFlow()

        override suspend fun updateServerUrl(url: String?) {
            _serverUrl.value = url
            sharedPreferences.edit().putString("server_url", url).apply()
        }

        override fun observeUseAutoDiscovery(): Flow<Boolean> = _useAutoDiscovery.asStateFlow()

        override suspend fun updateUseAutoDiscovery(useAutoDiscovery: Boolean) {
            _useAutoDiscovery.value = useAutoDiscovery
            sharedPreferences.edit().putBoolean("use_auto_discovery", useAutoDiscovery).apply()
        }

        override fun observeServerUrls(): Flow<List<String>> = _serverUrls.asStateFlow()

        override suspend fun getServerUrls(): List<String> = _serverUrls.value

        override suspend fun saveServerUrls(urls: List<String>) {
            _serverUrls.value = urls
            val jsonArray = JSONArray(urls)
            sharedPreferences.edit().putString("server_urls", jsonArray.toString()).apply()
        }

        override suspend fun addServerUrl(url: String) {
            val currentUrls = _serverUrls.value.toMutableList()
            if (!currentUrls.contains(url)) {
                currentUrls.add(url)
                saveServerUrls(currentUrls)
            }
        }

        override suspend fun removeServerUrl(url: String) {
            val currentUrls = _serverUrls.value.toMutableList()
            currentUrls.remove(url)
            saveServerUrls(currentUrls)
        }

        private fun loadServerUrls(): List<String> {
            val jsonString = sharedPreferences.getString("server_urls", null) ?: return emptyList()
            return try {
                val jsonArray = JSONArray(jsonString)
                List(jsonArray.length()) { jsonArray.getString(it) }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
