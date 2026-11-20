package com.karianoot.app

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * When the app crashes, the details are written to a file on the phone (nothing is sent anywhere). On the next start
 * the studio asks whether to send them (the user picks how: WhatsApp, mail...) or to forget them.
 */
class CrashLog(private val ctx: Context) {
    private val file = File(ctx.filesDir, "last_crash.txt")
    /** The last crash's report, until the user sends or dismisses it. */
    val pending = MutableStateFlow(file.takeIf { it.exists() }?.readText())

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { file.writeText(report(t, e)) }
            previous?.uncaughtException(t, e)
        }
    }

    fun dismiss() { file.delete(); pending.value = null }

    private fun report(t: Thread, e: Throwable): String {
        val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode }.getOrDefault(0)
        return "קריינות – דוח תקלה\nגרסה: $version\nמכשיר: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n" +
            "זמן: ${java.util.Date()}\nתהליך: ${t.name}\n\n" + sw.toString().take(12_000)
    }
}
