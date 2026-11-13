package com.aura.aura_ui.agentcore

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.aura.aura_ui.accessibility.AuraAccessibilityService
import com.aura.aura_ui.accessibility.RestrictedAppAccessibilityGate

/**
 * Whether AURA can use the phone: its accessibility service, which every tap, swipe, typed word
 * and screen read goes through.
 *
 * - Contract: [isOn] reads the system's list of enabled services, so it is right even before the
 *   service has connected. [openSettings] opens the accessibility list with AURA's row picked
 *   out where Settings supports it (an app cannot open AURA's own switch directly).
 * - Change together: `ToolHub.dispatch` (the [OFF_ERROR] text it rewrites), `AccessChip` in
 *   `ChatUi.kt`, the Accessibility row in `MonoSettingsScreen.kt`.
 */
object PhoneAccess {
    /** What the bridges return when the service is not connected. */
    const val OFF_ERROR = "Accessibility service not running"

    /** What the model is told instead, so it sends the user to the card rather than retrying. */
    const val MODEL_MESSAGE =
        "AURA's accessibility service is off, so it cannot tap, type or read the screen. Do not retry. " +
            "Tell the user in one line to tap Turn on in the card above the message box, then ask again."

    fun isOn(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            it.resolveInfo.serviceInfo.packageName == context.packageName &&
                it.resolveInfo.serviceInfo.name == AuraAccessibilityService::class.java.name
        }
    }.getOrDefault(false)

    fun openSettings(context: Context) {
        runCatching {
            context.startActivity(RestrictedAppAccessibilityGate.reEnableIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Toast.makeText(context, "Turn on AURA", Toast.LENGTH_SHORT).show()
        }
    }
}

/** Whether AURA's accessibility service is on, re-read each time the app comes back to the front. */
@androidx.compose.runtime.Composable
fun rememberPhoneAccess(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var on by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(PhoneAccess.isOn(context)) }
    androidx.compose.runtime.DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) on = PhoneAccess.isOn(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return on
}
