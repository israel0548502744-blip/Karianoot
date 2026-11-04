package com.karianoot.app

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.karianoot.app.ui.App

class MainActivity : ComponentActivity() {
    private lateinit var models: Models
    private lateinit var speech: Speech

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        models = Models(applicationContext)
        speech = Speech(applicationContext, models)
        // Download progress shows as a notification.
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val shared = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)
        setContent { App(models, speech, shared) }
    }

    override fun onDestroy() {
        if (isFinishing) speech.release()
        super.onDestroy()
    }
}
