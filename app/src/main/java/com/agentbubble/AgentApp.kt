package com.agentbubble

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Catches unexpected crashes, saves the stack trace on the device and offers to show it on
 * the next launch, so bugs can be reported without plugging the phone into a computer.
 */
class AgentApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                saveCrash(this, throwable)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val FILE = "last_crash.txt"

        fun crashFile(ctx: Context): File = File(ctx.filesDir, FILE)

        fun saveCrash(ctx: Context, t: Throwable) {
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                pw.println("Local Phone Operator crash report")
                pw.println(
                    "When: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                )
                pw.println(
                    "Android: " + android.os.Build.VERSION.RELEASE +
                        " (SDK " + android.os.Build.VERSION.SDK_INT + ")"
                )
                pw.println("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                pw.println()
                t.printStackTrace(pw)
                pw.flush()
                crashFile(ctx).writeText(sw.toString())
            } catch (_: Throwable) {
            }
        }
    }
}
