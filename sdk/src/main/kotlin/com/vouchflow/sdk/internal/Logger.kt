package com.vouchflow.sdk.internal

import android.util.Log

internal object VouchflowLogger {
    private const val TAG = "VouchflowSDK"

    fun debug(message: String) {
        Log.d(TAG, message)
    }

    fun warn(message: String) {
        Log.w(TAG, message)
    }

    fun error(message: String) {
        Log.e(TAG, message)
    }
}
