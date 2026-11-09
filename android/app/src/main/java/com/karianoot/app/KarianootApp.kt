package com.karianoot.app

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.karianoot.engine.BlueTts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the narration job is doing right now; the UI and the notification both read it. */
data class JobState(
    val busy: Boolean = false,
    val stage: String = "",
    val progress: Float = 0f,
    val result: Creation? = null,
    val error: String? = null,
    val startedAt: Long = 0L,
)

/**
 * Owns the models, the engine and the running job, so a narration keeps going when the user
 * leaves the app: the job runs here, and [NarrationService] keeps the process alive with a
 * progress notification until it is done.
 */
class KarianootApp : Application() {
    lateinit var models: Models
    lateinit var speech: Speech
    lateinit var voices: Voices
    lateinit var pronounce: Pronunciations
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _job = MutableStateFlow(JobState())
    val job: StateFlow<JobState> = _job

    /** True while one of the app's screens is visible (no "ready" notification then). */
    @Volatile var inForeground = false
        private set
    private var started = 0

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: android.app.Activity) {
                started++; inForeground = true
                // Back in the app: the "ready" notification has done its job.
                getSystemService(android.app.NotificationManager::class.java).cancel(Notify.DONE_ID)
            }
            override fun onActivityStopped(a: android.app.Activity) { started = maxOf(0, started - 1); inForeground = started > 0 }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        models = Models(this)
        pronounce = Pronunciations(this)
        speech = Speech(this, models) { pronounce.list.value }
        voices = Voices(this, models)
        Notify.createChannels(this)
    }

    fun narrate(text: String, voice: VoiceInfo, opt: BlueTts.Options) {
        if (_job.value.busy) return
        _job.value = JobState(busy = true, stage = "מתחיל…", startedAt = System.currentTimeMillis())
        ContextCompat.startForegroundService(this, Intent(this, NarrationService::class.java))
        scope.launch {
            runCatching {
                speech.speak(text, voice, opt) { s, p -> _job.value = _job.value.copy(stage = s, progress = p) }
            }.onSuccess { c -> _job.value = JobState(result = c) }
                .onFailure { e -> _job.value = JobState(error = if (speech.wasCancelled()) "הקריינות בוטלה" else e.message ?: e.toString()) }
        }
    }

    /** Stops the narration being made (between pieces for Blue, within a second for Qwen). */
    fun cancelNarration() { if (_job.value.busy) { _job.value = _job.value.copy(stage = "מבטל…"); speech.cancel() } }

    /** The voice whose sample is being made, or null. */
    val previewing = MutableStateFlow<String?>(null)

    fun previewVoice(v: VoiceInfo, done: (java.io.File?) -> Unit) {
        if (previewing.value != null || _job.value.busy) return
        previewing.value = v.id
        scope.launch {
            val f = runCatching { speech.preview(v) }.getOrNull()
            previewing.value = null
            kotlinx.coroutines.withContext(Dispatchers.Main) { done(f) }
        }
    }

    /** Making a voice from a recording; null when idle, otherwise a status line. */
    val cloning = MutableStateFlow<String?>(null)

    fun createVoice(name: String, uri: android.net.Uri, personalOnly: Boolean, female: Boolean, done: (String) -> Unit) {
        if (cloning.value != null) return
        cloning.value = "מכין את הקול…"
        scope.launch {
            val qwenPrint: ((FloatArray) -> FloatArray)? = if (models.isReady(Catalog.qwen.id))
                { wav -> kotlinx.coroutines.runBlocking { speech.qwenVoicePrint(wav) } } else null
            val msg = runCatching { voices.create(name, uri, personalOnly, female, qwenPrint) }
                .fold({ "הקול \"${it.name}\" מוכן ✨" }, { "לא הצלחתי ליצור את הקול: ${it.message}" })
            cloning.value = null
            kotlinx.coroutines.withContext(Dispatchers.Main) { done(msg) }
        }
    }

    /** Re-making one sentence of a recording: (file, sentence index) while it runs. */
    val redoing = MutableStateFlow<Pair<java.io.File, Int>?>(null)

    fun redoPart(c: Creation, i: Int, done: (Creation?) -> Unit) {
        if (redoing.value != null || _job.value.busy) return
        val voice = (Catalog.voices + voices.custom.value).firstOrNull { it.id == c.voiceId } ?: return done(null)
        redoing.value = c.file to i
        scope.launch {
            val r = runCatching { speech.redoPart(c, i, voice) }.getOrNull()
            if (r != null && _job.value.result?.file == c.file) _job.value = _job.value.copy(result = r)
            redoing.value = null
            kotlinx.coroutines.withContext(Dispatchers.Main) { done(r) }
        }
    }

    fun clearResult() { if (!_job.value.busy) _job.value = JobState() }
}
