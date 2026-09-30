package com.aura.aura_ui.data.preferences

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages onboarding/first launch preferences
 * Persists across app restarts using SharedPreferences
 */
object OnboardingPreferences {
    
    private const val PREFS_NAME = "aura_onboarding_prefs"
    private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
    private const val KEY_FIRST_LAUNCH_TIMESTAMP = "first_launch_timestamp"
    
    private var prefs: SharedPreferences? = null
    
    private val _hasCompletedOnboarding = MutableStateFlow(false)
    val hasCompletedOnboarding: StateFlow<Boolean> = _hasCompletedOnboarding.asStateFlow()
    
    /**
     * Initialize preferences with context
     * Call this in Application.onCreate() or MainActivity.onCreate()
     */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _hasCompletedOnboarding.value = prefs?.getBoolean(KEY_ONBOARDING_COMPLETED, false) ?: false
    }
    
    /**
     * Check if onboarding has been completed
     */
    fun isOnboardingCompleted(): Boolean {
        return prefs?.getBoolean(KEY_ONBOARDING_COMPLETED, false) ?: false
    }
    
    /**
     * Mark onboarding as completed
     * Call this when user finishes the welcome/get started screen
     */
    fun completeOnboarding() {
        prefs?.edit()?.apply {
            putBoolean(KEY_ONBOARDING_COMPLETED, true)
            putLong(KEY_FIRST_LAUNCH_TIMESTAMP, System.currentTimeMillis())
            apply()
        }
        _hasCompletedOnboarding.value = true
    }
    
    /**
     * Reset onboarding (for testing or settings option)
     */
    fun resetOnboarding() {
        prefs?.edit()?.apply {
            putBoolean(KEY_ONBOARDING_COMPLETED, false)
            apply()
        }
        _hasCompletedOnboarding.value = false
    }
    
    /**
     * Get the timestamp of first launch (after onboarding)
     */
    fun getFirstLaunchTimestamp(): Long {
        return prefs?.getLong(KEY_FIRST_LAUNCH_TIMESTAMP, 0L) ?: 0L
    }
}
