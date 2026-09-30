package com.aura.aura_ui.utils

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Industry-standard structured logger for AURA agent subsystems.
 * Format: [TIMESTAMP] LEVEL [COMPONENT] - MESSAGE {context}
 */
object AgentLogger {
    // Component tags (industry-standard naming)
    const val COMPONENT_SCREENSHOT = "Screenshot"
    const val COMPONENT_UI_TREE = "UITree"
    const val COMPONENT_AUDIO = "Audio"
    const val COMPONENT_ACCESSIBILITY = "Accessibility"
    const val COMPONENT_DEEPLINK = "Deeplink"
    const val COMPONENT_CONFIG = "Config"
    const val COMPONENT_CONNECTION = "Connection"

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * Log debug message with structured format
     */
    fun d(
        component: String,
        message: String,
        context: Map<String, Any>? = null,
    ) {
        val formattedMessage = formatMessage(message, context)
        Log.d(component, formattedMessage)
    }

    /**
     * Log info message with structured format
     */
    fun i(
        component: String,
        message: String,
        context: Map<String, Any>? = null,
    ) {
        val formattedMessage = formatMessage(message, context)
        Log.i(component, formattedMessage)
    }

    /**
     * Log warning message with structured format
     */
    fun w(
        component: String,
        message: String,
        context: Map<String, Any>? = null,
    ) {
        val formattedMessage = formatMessage(message, context)
        Log.w(component, formattedMessage)
    }

    /**
     * Log error message with structured format
     */
    fun e(
        component: String,
        message: String,
        throwable: Throwable? = null,
        context: Map<String, Any>? = null,
    ) {
        val formattedMessage = formatMessage(message, context)
        if (throwable != null) {
            Log.e(component, formattedMessage, throwable)
        } else {
            Log.e(component, formattedMessage)
        }
    }

    /**
     * Screenshot capture component logging
     */
    object Screen {
        fun d(
            message: String,
            context: Map<String, Any>? = null,
        ) = AgentLogger.d(COMPONENT_SCREENSHOT, stripEmojis(message), context)

        fun i(
            message: String,
            context: Map<String, Any>? = null,
        ) = AgentLogger.i(COMPONENT_SCREENSHOT, stripEmojis(message), context)

        fun w(
            message: String,
            context: Map<String, Any>? = null,
        ) = AgentLogger.w(COMPONENT_SCREENSHOT, stripEmojis(message), context)

        fun e(
            message: String,
            throwable: Throwable? = null,
            context: Map<String, Any>? = null,
        ) = AgentLogger.e(COMPONENT_SCREENSHOT, stripEmojis(message), throwable, context)
    }

    /**
     * UI Tree extraction component logging
     */
    object UI {
        fun d(
            message: String,
            context: Map<String, Any>? = null,
        ) = AgentLogger.d(COMPONENT_UI_TREE, stripEmojis(message), context)

        fun i(
            message: String,
            context: Map<String, Any>? = null,
        ) = AgentLogger.i(COMPONENT_UI_TREE, stripEmojis(message), context)

        fun w(
            message: String,
            context: Map<String, Any>? = null,
        ) = AgentLogger.w(COMPONENT_UI_TREE, stripEmojis(message), context)

        fun e(
            message: String,
            throwable: Throwable? = null,
            context: Map<String, Any>? = null,
        ) = AgentLogger.e(COMPONENT_UI_TREE, stripEmojis(message), throwable, context)
    }

    /**
     * Audio processing component logging
     */
    object Audio {
        fun d(
            message: String,
            context: Map<String, Any>? = null,
        ) = AgentLogger.d(COMPONENT_AUDIO, stripEmojis(message), context)

        fun i(
            message: String,
            context: Map<String, Any>? = null,
        ) = AgentLogger.i(COMPONENT_AUDIO, stripEmojis(message), context)

        fun w(
            message: String,
            context: Map<String, Any>? = null,
        ) = AgentLogger.w(COMPONENT_AUDIO, stripEmojis(message), context)

        fun e(
            message: String,
            throwable: Throwable? = null,
            context: Map<String, Any>? = null,
        ) = AgentLogger.e(COMPONENT_AUDIO, stripEmojis(message), throwable, context)
    }

    /**
     * Accessibility service component logging
     */
    object Auto {
        fun d(
            message: String,
            context: Map<String, Any>? = null,
        ) = AgentLogger.d(COMPONENT_ACCESSIBILITY, stripEmojis(message), context)

        fun i(
            message: String,
            context: Map<String, Any>? = null,
        ) = AgentLogger.i(COMPONENT_ACCESSIBILITY, stripEmojis(message), context)

        fun w(
            message: String,
            context: Map<String, Any>? = null,
        ) = AgentLogger.w(COMPONENT_ACCESSIBILITY, stripEmojis(message), context)

        fun e(
            message: String,
            throwable: Throwable? = null,
            context: Map<String, Any>? = null,
        ) = AgentLogger.e(COMPONENT_ACCESSIBILITY, stripEmojis(message), throwable, context)
    }

    /**
     * Deep link handling component logging
     */
    object Deeplink {
        fun d(
            message: String,
            context: Map<String, Any>? = null,
        ) = AgentLogger.d(COMPONENT_DEEPLINK, stripEmojis(message), context)

        fun i(
            message: String,
            context: Map<String, Any>? = null,
        ) = AgentLogger.i(COMPONENT_DEEPLINK, stripEmojis(message), context)

        fun w(
            message: String,
            context: Map<String, Any>? = null,
        ) = AgentLogger.w(COMPONENT_DEEPLINK, stripEmojis(message), context)

        fun e(
            message: String,
            throwable: Throwable? = null,
            context: Map<String, Any>? = null,
        ) = AgentLogger.e(COMPONENT_DEEPLINK, stripEmojis(message), throwable, context)
    }

    object Net {
        fun d(message: String, context: Map<String, Any>? = null) =
            AgentLogger.d(COMPONENT_CONNECTION, message, context)
        fun i(message: String, context: Map<String, Any>? = null) =
            AgentLogger.i(COMPONENT_CONNECTION, message, context)
        fun w(message: String, context: Map<String, Any>? = null) =
            AgentLogger.w(COMPONENT_CONNECTION, message, context)
        fun e(message: String, throwable: Throwable? = null, context: Map<String, Any>? = null) =
            AgentLogger.e(COMPONENT_CONNECTION, message, throwable, context)
    }

    /**
     * Configuration component logging
     */
    object Config {
        fun d(
            message: String,
            context: Map<String, Any>? = null,
        ) = AgentLogger.d(COMPONENT_CONFIG, stripEmojis(message), context)

        fun i(
            message: String,
            context: Map<String, Any>? = null,
        ) = AgentLogger.i(COMPONENT_CONFIG, stripEmojis(message), context)

        fun w(
            message: String,
            context: Map<String, Any>? = null,
        ) = AgentLogger.w(COMPONENT_CONFIG, stripEmojis(message), context)

        fun e(
            message: String,
            throwable: Throwable? = null,
            context: Map<String, Any>? = null,
        ) = AgentLogger.e(COMPONENT_CONFIG, stripEmojis(message), throwable, context)
    }

    /**
     * Format message with structured context in industry-standard format
     * Format: MESSAGE {key1=value1, key2=value2}
     */
    private fun formatMessage(
        message: String,
        context: Map<String, Any>?,
    ): String {
        return if (context.isNullOrEmpty()) {
            message
        } else {
            val contextStr = context.entries.joinToString(", ") { "${it.key}=${it.value}" }
            "$message {$contextStr}"
        }
    }

    /**
     * Strip emojis and decorative characters for professional logs
     */
    private fun stripEmojis(message: String): String {
        return message
            .replace("✅", "SUCCESS:")
            .replace("❌", "ERROR:")
            .replace("⚠️", "WARNING:")
            .replace("📸", "")
            .replace("📱", "")
            .replace("🔊", "")
            .replace("⚡", "")
            .replace("📤", "")
            .replace("📥", "")
            .replace("🔄", "")
            .replace("📊", "")
            .replace("🔇", "")
            .replace("🎤", "")
            .replace("🎵", "")
            .replace("▶️", "")
            .replace("🚫", "")
            .replace("📷", "")
            .replace("📝", "")
            .replace("🔐", "")
            .replace("📺", "")
            .replace("🖼️", "")
            .replace("🚀", "")
            .replace("🔌", "")
            .replace("🎯", "")
            .replace("🤖", "")
            .replace("📋", "")
            .replace("💬", "")
            .replace("🔍", "")
            .trim()
            .replace(Regex("\\s+"), " ") // Collapse multiple spaces
    }
}
