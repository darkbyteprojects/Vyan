package com.vyan.xtreamplayer.utils

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashReporter private constructor(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val stackTrace = Log.getStackTraceString(throwable)
            val timestamp = SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                Locale.getDefault()
            ).format(Date())
            val formattedLog = "Timestamp: $timestamp\nThread: ${thread.name}\n\n$stackTrace"

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_CRASH_LOG, formattedLog).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val PREFS_NAME = "CrashLogs"
        private const val KEY_CRASH_LOG = "last_crash_log"

        fun init(context: Context) {
            val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
            if (currentHandler !is CrashReporter) {
                val crashReporter = CrashReporter(context.applicationContext, currentHandler)
                Thread.setDefaultUncaughtExceptionHandler(crashReporter)
            }
        }

        fun getCrashLog(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_CRASH_LOG, null)
        }

        fun clearCrashLog(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_CRASH_LOG).apply()
        }
    }
}