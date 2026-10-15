package com.likewa.gamelingo

import android.app.Application
import android.os.Build
import java.io.File

// Saves the stack trace of any crash so MainActivity can show it on the next launch.
class GameLingoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                crashFile(this).writeText(
                    "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                        "Thread: ${thread.name}\n\n" + error.stackTraceToString()
                )
            } catch (_: Exception) {}
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        fun crashFile(app: Application) = File(app.filesDir, "last_crash.txt")
    }
}
