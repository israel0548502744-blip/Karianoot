package com.karianoot.app

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.karianoot.app.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as KarianootApp
        // Download progress and "narration ready" show as notifications.
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val shared = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)
        // "Remove animations" in the phone's settings (accessibility) turns the app's moving effects off too.
        val motion = android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        setContent { App(app, shared, motion = motion, live = true) }
    }
}
