package com.hermex.v3.domain.util

import android.util.Log

class Logger {
    fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
    }

    fun e(tag: String, message: String) {
        Log.e(tag, message)
    }
}
