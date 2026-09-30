package com.aura.aura_ui.accessibility

import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import com.aura.aura_ui.utils.AgentLogger

class SystemControlsHelper(private val context: Context) {

    private var isFlashlightOn = false

    fun toggleWifi(enable: Boolean?) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                AgentLogger.Auto.d("WiFi toggle requested on Android 10+, opening connectivity panel")
                val intent = Intent(Settings.Panel.ACTION_WIFI)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            }

            @Suppress("DEPRECATION")
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            when (enable) {
                true -> wifiManager.isWifiEnabled = true
                false -> wifiManager.isWifiEnabled = false
                null -> wifiManager.isWifiEnabled = !wifiManager.isWifiEnabled
            }
            AgentLogger.Auto.d("WiFi toggled", mapOf("enabled" to (enable?.toString() ?: "toggle")))
        } catch (e: Exception) {
            AgentLogger.Auto.e("Error toggling WiFi", e)
        }
    }

    fun toggleBluetooth(enable: Boolean?) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                AgentLogger.Auto.d("Bluetooth toggle requested on Android 13+, opening bluetooth settings")
                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            }

            @Suppress("DEPRECATION")
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            bluetoothAdapter ?: return

            @Suppress("DEPRECATION")
            when (enable) {
                true -> bluetoothAdapter.enable()
                false -> bluetoothAdapter.disable()
                null -> if (bluetoothAdapter.isEnabled) bluetoothAdapter.disable() else bluetoothAdapter.enable()
            }
            AgentLogger.Auto.d("Bluetooth toggled", mapOf("enabled" to (enable?.toString() ?: "toggle")))
        } catch (e: Exception) {
            AgentLogger.Auto.e("Error toggling Bluetooth", e)
        }
    }

    fun toggleFlashlight() {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return
            isFlashlightOn = !isFlashlightOn
            cameraManager.setTorchMode(cameraId, isFlashlightOn)
            AgentLogger.Auto.d("Flashlight toggled", mapOf("enabled" to isFlashlightOn))
        } catch (e: Exception) {
            AgentLogger.Auto.e("Error toggling flashlight", e)
        }
    }

    fun adjustVolume(direction: Int) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.adjustVolume(direction, AudioManager.FLAG_SHOW_UI)
        } catch (e: Exception) {
            AgentLogger.Auto.e("Error adjusting volume", e)
        }
    }

    fun adjustBrightness(increase: Boolean) {
        try {
            val resolver = context.contentResolver
            val current = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            val delta = if (increase) 32 else -32
            val newValue = (current + delta).coerceIn(10, 255)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, newValue)
        } catch (e: Exception) {
            AgentLogger.Auto.e("Error adjusting brightness", e)
        }
    }

    fun setDoNotDisturb(enable: Boolean) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (notificationManager.isNotificationPolicyAccessGranted) {
                val filter = if (enable) {
                    NotificationManager.INTERRUPTION_FILTER_NONE
                } else {
                    NotificationManager.INTERRUPTION_FILTER_ALL
                }
                notificationManager.setInterruptionFilter(filter)
                AgentLogger.Auto.d("Do Not Disturb set", mapOf("enabled" to enable))
            } else {
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                AgentLogger.Auto.w("DND access not granted, opening settings")
            }
        } catch (e: Exception) {
            AgentLogger.Auto.e("Error setting Do Not Disturb", e)
        }
    }

    fun toggleDoNotDisturb() {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (notificationManager.isNotificationPolicyAccessGranted) {
                val currentFilter = notificationManager.currentInterruptionFilter
                val isCurrentlyEnabled = currentFilter != NotificationManager.INTERRUPTION_FILTER_ALL
                setDoNotDisturb(!isCurrentlyEnabled)
            } else {
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            AgentLogger.Auto.e("Error toggling Do Not Disturb", e)
        }
    }

    fun setAutoRotate(enable: Boolean) {
        try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                if (enable) 1 else 0
            )
            AgentLogger.Auto.d("Auto-rotate set", mapOf("enabled" to enable))
        } catch (e: Exception) {
            AgentLogger.Auto.e("Error setting auto-rotate", e)
        }
    }

    fun toggleAutoRotate() {
        try {
            val currentValue = Settings.System.getInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                0
            )
            setAutoRotate(currentValue == 0)
        } catch (e: Exception) {
            AgentLogger.Auto.e("Error toggling auto-rotate", e)
        }
    }

    fun openAirplaneModeSettings() {
        try {
            val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AgentLogger.Auto.d("Opened airplane mode settings")
        } catch (e: Exception) {
            AgentLogger.Auto.e("Error opening airplane mode settings", e)
        }
    }

    fun openBatterySaverSettings() {
        try {
            val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AgentLogger.Auto.d("Opened battery saver settings")
        } catch (e: Exception) {
            AgentLogger.Auto.e("Error opening battery saver settings", e)
        }
    }

    fun openDisplaySettings() {
        try {
            val intent = Intent(Settings.ACTION_DISPLAY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AgentLogger.Auto.d("Opened display settings")
        } catch (e: Exception) {
            AgentLogger.Auto.e("Error opening display settings", e)
        }
    }

    fun openLocationSettings() {
        try {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AgentLogger.Auto.d("Opened location settings")
        } catch (e: Exception) {
            AgentLogger.Auto.e("Error opening location settings", e)
        }
    }

    fun openMobileDataSettings() {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
            } else {
                Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AgentLogger.Auto.d("Opened mobile data settings")
        } catch (e: Exception) {
            AgentLogger.Auto.e("Error opening mobile data settings", e)
        }
    }

    fun openHotspotSettings() {
        try {
            val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AgentLogger.Auto.d("Opened hotspot settings")
        } catch (e: Exception) {
            AgentLogger.Auto.e("Error opening hotspot settings", e)
        }
    }

    fun openNfcSettings() {
        try {
            val intent = Intent(Settings.ACTION_NFC_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AgentLogger.Auto.d("Opened NFC settings")
        } catch (e: Exception) {
            AgentLogger.Auto.e("Error opening NFC settings", e)
        }
    }

    fun openSettings() {
        try {
            val intent = Intent(Settings.ACTION_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AgentLogger.Auto.d("Opened main settings")
        } catch (e: Exception) {
            AgentLogger.Auto.e("Error opening settings", e)
        }
    }

    fun openWifiSettings() {
        try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AgentLogger.Auto.d("Opened WiFi settings")
        } catch (e: Exception) {
            AgentLogger.Auto.e("Error opening WiFi settings", e)
        }
    }

    fun openBluetoothSettings() {
        try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AgentLogger.Auto.d("Opened Bluetooth settings")
        } catch (e: Exception) {
            AgentLogger.Auto.e("Error opening Bluetooth settings", e)
        }
    }
}
