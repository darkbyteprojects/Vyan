package com.vyan.xtreamplayer

import android.app.Application
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class XtreamApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 1. Capture the system's default crash handler
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        // 2. Set our own global interceptor
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Log to Android Studio Logcat
                Log.e("XtreamCrash", "FATAL CRASH caught on thread: ${thread.name}", throwable)

                // Save the crash to a physical text file on the device
                writeCrashToFile(throwable)

            } catch (e: Exception) {
                Log.e("XtreamCrash", "Failed to write crash log to storage", e)
            } finally {
                // 3. Crucial: Hand the crash back to Android OS so it doesn't freeze the device
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun writeCrashToFile(throwable: Throwable) {
        // Creates a file like "crash_2026-08-18_19-30-00.txt"
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val crashFileName = "crash_$timestamp.txt"

        // Save it in the app's cache directory (accessible via Device File Explorer)
        val crashFile = File(cacheDir, crashFileName)

        FileWriter(crashFile, true).use { writer ->
            writer.append("=== XTREAM PLAYER CRASH LOG ===\n")
            writer.append("Time: $timestamp\n")
            writer.append("Exception: ${throwable.javaClass.name}\n")
            writer.append("Message: ${throwable.message}\n\n")
            writer.append("Stacktrace:\n")
            writer.append(Log.getStackTraceString(throwable))
            writer.append("\n===============================\n")
        }
    }
}