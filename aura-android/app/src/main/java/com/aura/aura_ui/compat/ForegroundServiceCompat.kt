package com.aura.aura_ui.compat

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Decides which foreground-service type mask is *safe* to claim right now.
 *
 * Android 14 (API 34) turned FGS types into an enforced contract: claiming
 * `microphone` without a live RECORD_AUDIO grant throws `SecurityException`
 * out of `startForeground()`, which — from `onCreate`/`onStartCommand` — takes
 * the whole process down. Since AURA's mic permission is revocable at any time
 * (and Tier-2 users may never grant it), the type has to be computed, not fixed.
 *
 * Pure function: no framework calls, so every OS × permission combination is
 * covered by unit tests instead of by hardware we do not own.
 */
object ForegroundTypePolicy {

    fun resolve(declaredTypes: Int, micGranted: Boolean, sdkInt: Int): Int {
        // The type parameter only exists from API 29 onwards.
        if (sdkInt < Build.VERSION_CODES.Q) return 0
        // Enforcement (and the SecurityException) starts at API 34. Below that,
        // narrowing the mask would change behaviour for no benefit.
        if (sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return declaredTypes
        if (micGranted) return declaredTypes

        val withoutMic = declaredTypes and
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE.inv()

        // A mic-only service still has to claim *some* type on API 34+.
        // specialUse is declared by every AURA service that uses the mic.
        return if (withoutMic != 0 || declaredTypes == 0) {
            withoutMic
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
    }
}

/**
 * Crash-proof wrappers around the two foreground-service calls that Android 12+
 * and 14+ can reject at runtime.
 *
 * ## The two failure modes this closes
 * 1. **`Context.startForegroundService()`** throws
 *    `ForegroundServiceStartNotAllowedException` (API 31+) when the app is in the
 *    background and holds no exemption. Boot receivers, wake-word triggers and
 *    MCP reconnects all call from exactly that state. Unguarded, the *caller*
 *    crashes.
 * 2. **`Service.startForeground()`** throws `SecurityException` /
 *    `MissingForegroundServiceTypeException` (API 34+) when the claimed type is
 *    not backed by a live permission. Unguarded, the *service* crashes on
 *    creation — which, for AURA, means the assistant overlay never appears and
 *    the user just sees "nothing happened".
 *
 * Both degrade to `false` here. Callers decide what "no foreground service"
 * means for them; nobody takes the process down.
 */
object ForegroundServiceCompat {

    private const val TAG = "FgsCompat"

    /**
     * Start [intent] as a foreground service without letting a background-start
     * rejection escape.
     *
     * @return true when the start request was accepted by the system.
     */
    fun startSafely(context: Context, intent: Intent): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        true
    } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException (API 31+) is a subclass of
        // IllegalStateException; catching broadly also covers OEM skins that
        // throw SecurityException for the same situation.
        Log.w(TAG, "Foreground start refused for ${intent.component?.shortClassName}: ${e.message}")
        false
    }

    /**
     * Enter the foreground with a type mask narrowed to what this device will
     * actually accept ([ForegroundTypePolicy]).
     *
     * @param declaredTypes the `foregroundServiceType` mask from the manifest.
     * @return true when the service is now in the foreground.
     */
    fun Service.startForegroundSafely(
        notificationId: Int,
        notification: Notification,
        declaredTypes: Int,
    ): Boolean {
        val micGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

        val type = ForegroundTypePolicy.resolve(declaredTypes, micGranted, Build.VERSION.SDK_INT)

        return try {
            ServiceCompat.startForeground(this, notificationId, notification, type)
            true
        } catch (e: Exception) {
            Log.w(TAG, "startForeground(type=$type) rejected: ${e.javaClass.simpleName}: ${e.message}")
            // Last resort: claim nothing beyond specialUse. On API 34+ a bare
            // retry with the same mask would fail identically, so only retry if
            // we can actually change the request.
            if (type != ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) {
                try {
                    ServiceCompat.startForeground(
                        this,
                        notificationId,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                    )
                    Log.i(TAG, "startForeground succeeded after downgrade to specialUse")
                    return true
                } catch (retry: Exception) {
                    Log.w(TAG, "specialUse downgrade also rejected: ${retry.message}")
                }
            }
            false
        }
    }
}
