package com.aura.aura_ui.overlay

import android.app.Activity
import android.os.Bundle

/**
 * What makes AURA pickable as the phone's digital assistant (Settings → Apps → Default apps →
 * Digital assistant app) and what the assist gesture — long-press power or home, on devices that
 * map it — opens. Android's assistant role qualifies any app with an ACTION_ASSIST activity, so
 * this invisible bounce is enough; a full VoiceInteractionService adds nothing AURA uses.
 *
 * Starting the overlay from an Activity also makes the service start foreground-eligible, which a
 * background broadcast would not be.
 */
class AssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AuraOverlayService.showAndListen(this, source = "assist")
        finish()
    }
}
