package com.aura.aura_ui.overlay

import android.app.Activity
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.aura.aura_ui.compat.OemCompat

/**
 * Invisible bounce activity: opens whichever settings screen owns the overlay
 * gate on *this* device, then finishes.
 *
 * An activity is required because vendor security-centre screens reject
 * `startActivity` from a service/notification context on several skins, and
 * because an Activity context lets Android attribute the launch to a user tap.
 */
class OverlayFixActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If the standard toggle is the thing that's off, that screen is both
        // the correct destination and the one guaranteed to exist.
        val opened = if (!Settings.canDrawOverlays(this)) {
            runCatching {
                startActivity(OemCompat.aospOverlaySettingsIntent(this))
            }.isSuccess
        } else {
            OemCompat.openOverlaySettings(this)
        }

        if (!opened) {
            Log.w("OverlayFixActivity", "No overlay settings screen could be opened")
            Toast.makeText(
                this,
                "Open Settings → Apps → AURA and allow it to display over other apps.",
                Toast.LENGTH_LONG,
            ).show()
        }
        finish()
    }
}
