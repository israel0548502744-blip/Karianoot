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
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _job = MutableStateFlow(JobState())
    val job: StateFlow<JobState> = _job

    override fun onCreate() {
        super.onCreate()
        models = Models(this)
        speech = Speech(this, models)
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

    fun clearResult() { if (!_job.value.busy) _job.value = JobState() }
}
