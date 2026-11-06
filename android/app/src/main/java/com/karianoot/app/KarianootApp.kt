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

    override fun onCreate() {
        super.onCreate()
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
                .onFailure { e -> _job.value = JobState(error = e.message ?: e.toString()) }
        }
    }

    /** Making a voice from a recording; null when idle, otherwise a status line. */
    val cloning = MutableStateFlow<String?>(null)

    fun createVoice(name: String, uri: android.net.Uri, personalOnly: Boolean, done: (String) -> Unit) {
        if (cloning.value != null) return
        cloning.value = "מכין את הקול…"
        scope.launch {
            val msg = runCatching { voices.create(name, uri, personalOnly) }
                .fold({ "הקול \"${it.name}\" מוכן ✨" }, { "לא הצלחתי ליצור את הקול: ${it.message}" })
            cloning.value = null
            kotlinx.coroutines.withContext(Dispatchers.Main) { done(msg) }
        }
    }

    fun clearResult() { if (!_job.value.busy) _job.value = JobState() }
}
