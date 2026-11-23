package com.karianoot.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.karianoot.app.ui.App

class MainActivity : ComponentActivity() {
    private val app get() = application as KarianootApp
    /** "הדבקה וקריינות" was tapped: read the clipboard once the window has focus (Android only allows it then). */
    private var pastePending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Download progress and "narration ready" show as notifications.
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        if (savedInstanceState == null) handle(intent)
        // "Remove animations" in the phone's settings (accessibility) turns the app's moving effects off too.
        val motion = android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        setContent { App(app, null, motion = motion, live = true) }
    }

    // The activity is singleTask: a second share / shortcut while it is open arrives here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        when (i?.action) {
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { app.incoming.value = KarianootApp.Incoming(it, false) }
            Intent.ACTION_PROCESS_TEXT -> i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.takeIf { it.isNotBlank() }
                ?.let { app.incoming.value = KarianootApp.Incoming(it, false) }
            Shortcuts.ACTION_PASTE -> pastePending = true
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && pastePending) {
            pastePending = false
            val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            if (text.isNullOrBlank()) android.widget.Toast.makeText(this, "אין טקסט מועתק. העתיקו טקסט ונסו שוב.", android.widget.Toast.LENGTH_LONG).show()
            else app.incoming.value = KarianootApp.Incoming(text, true)
        }
    }
}
