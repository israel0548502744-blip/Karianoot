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
    lateinit var voiceOrder: VoiceOrder
    lateinit var updates: Updates
    lateinit var crashes: CrashLog
    lateinit var lexicon: LexiconUpdates
    val backup by lazy { Backup(this) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _job = MutableStateFlow(JobState())
    val job: StateFlow<JobState> = _job

    /** True while one of the app's screens is visible (no "ready" notification then). */
    @Volatile var inForeground = false
        private set
    private var started = 0

    override fun onCreate() {
        super.onCreate()
        crashes = CrashLog(this).also { it.install() }
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
        voiceOrder = VoiceOrder(this)
        updates = Updates(this)
        speech = Speech(this, models) { pronounce.list.value }
        voices = Voices(this, models)
        lexicon = LexiconUpdates(this).also { it.loadCached() }
        Notify.createChannels(this)
        Shortcuts.install(this)
    }

    private val settings by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    /** Setting: play a narration as soon as it is ready (on by default). */
    var autoPlay: Boolean
        get() = settings.getBoolean("autoplay", true)
        set(v) { settings.edit().putBoolean("autoplay", v).apply() }
    /** A narration just finished and should start playing once its card is on screen. */
    val autoPlayPending = MutableStateFlow(false)

    /** Setting "הימור אחראי": a closing line read at the end of every narration (off by default). */
    var responsible: Boolean
        get() = settings.getBoolean("responsible", false)
        set(v) { settings.edit().putBoolean("responsible", v).apply() }
    var responsibleLine: String
        get() = settings.getString("responsible_line", null)?.takeIf { it.isNotBlank() } ?: RESPONSIBLE_DEFAULT
        set(v) { settings.edit().putString("responsible_line", v.trim()).apply() }
    /** Setting "סאונד אולפן" on every narration (off by default: the user chose the voices by ear as they are). */
    var studioAlways: Boolean
        get() = settings.getBoolean("studio", false)
        set(v) { settings.edit().putBoolean("studio", v).apply() }

    /** Text sent to the app from outside (share, text selection menu, "הדבקה וקריינות"); [narrate] = start right away. */
    data class Incoming(val text: String, val narrate: Boolean)
    val incoming = MutableStateFlow<Incoming?>(null)

    /** [text] with the closing line of "הימור אחראי" when it is on (and not already there). */
    fun withClosing(text: String): String {
        if (!responsible) return text
        val line = responsibleLine
        return if (text.trimEnd().endsWith(line)) text else text.trimEnd() + "\n" + line
    }

    fun narrate(text: String, voice: VoiceInfo, opt: BlueTts.Options, targetSeconds: Float = 0f, voice2: VoiceInfo? = null) {
        if (_job.value.busy) return
        _job.value = JobState(busy = true, stage = "מתחיל…", startedAt = System.currentTimeMillis())
        ContextCompat.startForegroundService(this, Intent(this, NarrationService::class.java))
        scope.launch {
            runCatching {
                speech.speak(withClosing(text), voice, opt, targetSeconds, studioAlways, voice2) { s, p -> _job.value = _job.value.copy(stage = s, progress = p) }
            }.onSuccess { c -> autoPlayPending.value = autoPlay; _job.value = JobState(result = c) }
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
        val id = c.parts.getOrNull(i)?.voice?.ifEmpty { null } ?: c.voiceId
        val voice = (Catalog.voices + voices.custom.value).map(voiceOrder::named).firstOrNull { it.id == id } ?: return done(null)
        redoing.value = c.file to i
        scope.launch {
            val r = runCatching { speech.redoPart(c, i, voice) }.getOrNull()
            if (r != null && _job.value.result?.file == c.file) _job.value = _job.value.copy(result = r)
            redoing.value = null
            kotlinx.coroutines.withContext(Dispatchers.Main) { done(r) }
        }
    }

    /** "עריכת סאונד" of [c]; [done] gets the new recording (null on failure). */
    fun polish(c: Creation, p: Polish, done: (Creation?) -> Unit) {
        scope.launch {
            val r = runCatching { speech.polish(c, p) }.getOrNull()
            kotlinx.coroutines.withContext(Dispatchers.Main) { done(r) }
        }
    }

    /** The video being made: (recording, progress 0..1), or null. */
    val video = MutableStateFlow<Pair<java.io.File, Float>?>(null)
    @Volatile private var videoCancel = false

    /** A vertical video of [c] with captions; [done] gets the MP4 (null on failure or cancel). */
    fun makeVideo(c: Creation, style: VideoMaker.Style, done: (java.io.File?, String?) -> Unit) {
        if (video.value != null) return
        videoCancel = false
        video.value = c.file to 0f
        scope.launch {
            val out = java.io.File(speech.videosDir, c.file.nameWithoutExtension + "_${System.currentTimeMillis() % 100000}.mp4")
            val r = runCatching {
                VideoMaker(this@KarianootApp).make(c.file, speech.captions(c), style, out, { p -> video.value = c.file to p }, { videoCancel })
                // Keep the newest 10 videos.
                speech.videosDir.listFiles { f -> f.extension == "mp4" }?.sortedByDescending { it.lastModified() }?.drop(10)?.forEach { it.delete() }
                out
            }
            video.value = null
            kotlinx.coroutines.withContext(Dispatchers.Main) { done(r.getOrNull(), r.exceptionOrNull()?.let { if (videoCancel) null else it.message ?: it.toString() }) }
        }
    }

    fun cancelVideo() { videoCancel = true }

    fun clearResult() { if (!_job.value.busy) _job.value = JobState() }

    companion object {
        const val RESPONSIBLE_DEFAULT = "ההימורים מותרים מגיל שמונה עשרה בלבד. הַמְּרוּ באחריות."
    }
}
