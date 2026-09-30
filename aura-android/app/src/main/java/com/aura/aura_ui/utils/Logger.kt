package com.aura.aura_ui.utils

import android.util.Log

/**
 * Simple logger implementation for the app
 */
interface Logger {
    fun d(
        tag: String,
        message: String,
    )

    fun e(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    )

    fun i(
        tag: String,
        message: String,
    )

    fun w(
        tag: String,
        message: String,
    )
}

class AndroidLogger : Logger {
    override fun d(
        tag: String,
        message: String,
    ) {
        Log.d(tag, message)
    }

    override fun e(
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        Log.e(tag, message, throwable)
    }

    override fun i(
        tag: String,
        message: String,
    ) {
        Log.i(tag, message)
    }

    override fun w(
        tag: String,
        message: String,
    ) {
        Log.w(tag, message)
    }
}
