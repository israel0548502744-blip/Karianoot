package com.karianoot.app

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import com.karianoot.engine.BlueTts
import com.karianoot.engine.BlueVoice
import com.karianoot.engine.HebrewG2P
import com.karianoot.engine.Joiner
import com.karianoot.engine.MagpieTts
import com.karianoot.engine.Pronounce
import com.karianoot.engine.QwenTts
import com.karianoot.engine.TextPrep
import com.karianoot.engine.Wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Where one sentence sits in a recording (samples), so it can be made again on its own. */
data class PartSpan(val text: String, val start: Int, val end: Int)

data class Creation(
    val file: File, val text: String, val voice: String, val seconds: Float, val createdAt: Long, val genSeconds: Float,
    val parts: List<PartSpan> = emptyList(), val voiceId: String = "", val steps: Int = 10, val speed: Float = 1f,
    val temperature: Float = 1f,
    /** The sentence pause used (seconds), so a retake of one sentence fits back in the same way. */
    val pause: Float = 0f,
)

/**
 * Keeps one loaded voice engine at a time (each Blue bundle takes a few hundred MB of memory)
 * and runs synthesis off the main thread.
 */
class Speech(private val ctx: Context, private val models: Models, private val fixes: () -> List<Pronounce.Fix> = { emptyList() }) {
    private val lock = Mutex()
    private var g2p: HebrewG2P? = null
    private var tts: BlueTts? = null
    private var ttsModel: String? = null

    val creationsDir = File(ctx.getExternalFilesDir(null), "creations").apply { mkdirs() }
    private val index = File(creationsDir, "index.json")

    private var qwen: QwenTts? = null
    private var magpie: MagpieTts? = null
    @Volatile private var cancelled = false

    /** Asks the running narration to stop. */
    fun cancel() { cancelled = true; qwen?.cancel(); magpie?.cancel() }
    fun wasCancelled() = cancelled

    /** Progress callback that also stops the work between pieces when [cancel] was asked. */
    private fun guard(onStage: (String, Float) -> Unit): (String, Float) -> Unit = { s, p ->
        if (cancelled) throw kotlinx.coroutines.CancellationException("cancelled")
        onStage(s, p)
    }

    private val prefs = ctx.getSharedPreferences("speed", Context.MODE_PRIVATE)

    /**
     * Seconds of work per second of audio, measured on this phone per model (smoothed), or null before the first
     * narration with that model. Used for the time estimate in the studio.
     */
    fun workPerSecond(modelId: String): Float? = prefs.getFloat(modelId, -1f).takeIf { it > 0f } ?: FIRST_GUESS[modelId]

    /** Rough time for [text] with [voice]: ~13 characters per second of speech. Null when not measured yet. */
    fun estimateSeconds(text: String, voice: VoiceInfo, speed: Float = 1f): Float? {
        val rate = workPerSecond(voice.modelId) ?: return null
        val audio = text.trim().length / 13f / speed.coerceAtLeast(0.5f)
        return audio * rate
    }

    private fun recordSpeed(modelId: String, workSec: Float, audioSec: Float) {
        if (audioSec < 1f) return
        val r = workSec / audioSec
        val old = prefs.getFloat(modelId, -1f).takeIf { it > 0f }
        prefs.edit().putFloat(modelId, if (old == null) r else 0.6f * old + 0.4f * r).apply()
    }

    /** The voice as the library shows it: name and model, since Qwen and Blue share names. */
    private fun label(v: VoiceInfo) = v.name + " · " + when (v.modelId) { Catalog.qwen.id -> "Qwen"; Catalog.magpie.id -> "Magpie"; "blue_v3" -> "V3"; else -> "2.5" }

    /** One loaded voice, whatever the model: splits the text into pieces and speaks each. */
    private interface Engine {
        val sampleRate: Int
        /** The model's own sentence pause (seconds), for texts too short to measure it on. */
        val naturalPause: Float
        /** The shortest sentence pause for this model (the Joiner's floor). */
        val minPause: Float
        /** [retake]: a new, different take (Qwen samples instead of its deterministic greedy pass). */
        fun parts(text: String, opt: BlueTts.Options, onProgress: (Float) -> Unit = {}, retake: Boolean = false): List<BlueTts.Part>
    }

    private fun loadG2p(onStage: (String, Float) -> Unit): HebrewG2P {
        if (g2p == null) {
            onStage("טוען את מנוע ההגייה…", 0f)
            g2p = HebrewG2P(File(models.dir(Catalog.g2p), "model_int8.onnx").path)
        }
        return g2p!!
    }

    private fun loadQwen(onStage: (String, Float) -> Unit): QwenTts {
        val g = loadG2p(onStage)
        qwen?.let { return it }
        onStage("טוען את Qwen (כמה שניות)…", 0f)
        tts?.close(); tts = null; ttsModel = null   // one big model in memory at a time
        magpie?.close(); magpie = null
        val dir = models.dir(Catalog.qwen)
        return QwenTts(File(dir, "qwen-talker-1.7b-base-Q4_K_M.gguf").path, File(dir, "qwen-tokenizer-12hz-Q4_K_M.gguf").path, g,
            threads = Runtime.getRuntime().availableProcessors().coerceIn(4, 8)).also { qwen = it }
    }

    private fun loadMagpie(onStage: (String, Float) -> Unit): MagpieTts {
        val g = loadG2p(onStage)
        magpie?.let { return it }
        onStage("טוען את Magpie…", 0f)
        tts?.close(); tts = null; ttsModel = null   // one big model in memory at a time
        qwen?.close(); qwen = null
        // 4 threads: the small per-step graphs get slower with more (magpie-tts.cpp's measurements); to tune on the phone.
        return MagpieTts(File(models.dir(Catalog.magpie), Catalog.MAGPIE_FILE).path, g, threads = 4).also { magpie = it }
    }

    /** Loads (or reuses) the engine for [voice] and its style. Call under [lock]. */
    private fun engineFor(voice: VoiceInfo, onStage: (String, Float) -> Unit): Engine {
        if (voice.modelId == Catalog.magpie.id) {
            val m = loadMagpie(onStage)
            return object : Engine {
                override val sampleRate = m.sampleRate
                override val naturalPause = MagpieTts.NATURAL_PAUSE
                override val minPause = MagpieTts.MIN_PAUSE
                // Every take is sampled (temperature 0.7), so a retake is simply a new random take.
                // No speed control in the model: pieces are time-stretched (pitch kept), as for Qwen.
                override fun parts(text: String, opt: BlueTts.Options, onProgress: (Float) -> Unit, retake: Boolean) =
                    m.synthesizeParts(text, voice.speaker, onProgress, cancelled = { cancelled }).map {
                        BlueTts.Part(it.text, com.karianoot.engine.TimeStretch.stretch(it.samples, opt.speed, m.sampleRate))
                    }
            }
        }
        if (voice.modelId == Catalog.qwen.id) {
            val q = loadQwen(onStage)
            val spk = QwenTts.readSpk((voice.customFile ?: assetFile(voice.asset!!)).readBytes())
            return object : Engine {
                override val sampleRate = q.sampleRate
                override val naturalPause = QwenTts.NATURAL_PAUSE
                override val minPause = QwenTts.MIN_PAUSE
                // Greedy decoding (0 word errors in the tests) gives the same audio every time, so a retake samples
                // at temperature 0.6, measured as accurate as greedy (ASR, 3 voices x 2 seeds).
                // Qwen has no speed control: pieces are time-stretched (pitch kept) to the chosen speed.
                override fun parts(text: String, opt: BlueTts.Options, onProgress: (Float) -> Unit, retake: Boolean) =
                    q.synthesizeParts(text, spk, if (retake) QwenTts.RETAKE_TEMPERATURE else 0f, onProgress).map {
                        BlueTts.Part(it.text, com.karianoot.engine.TimeStretch.stretch(it.samples, opt.speed, q.sampleRate))
                    }
            }
        }
        val g = loadG2p(onStage)
        qwen?.close(); qwen = null
        magpie?.close(); magpie = null
        // Voices made from a recording take their duration from Blue 2.5's z_ref predictor.
        val dpRef = File(models.dir(Catalog.clone), "duration_predictor.onnx").takeIf { voice.modelId == "blue_25" && it.length() == 2_151_232L }
        val key = voice.modelId + (if (dpRef != null) "+ref" else "")
        if (ttsModel != key) {
            onStage("טוען את הקול…", 0f)
            tts?.close(); tts = null; ttsModel = null
            tts = BlueTts(models.dir(Catalog.model(voice.modelId)), g, dpRef)
            ttsModel = key
        }
        val engine = tts!!
        val v = BlueVoice.load(voice.customFile ?: voice.asset?.let { assetFile(it) }
            ?: File(models.dir(Catalog.model(voice.modelId)), voice.file))
        return object : Engine {
            override val sampleRate = engine.sampleRate
            override val naturalPause = BlueTts.NATURAL_PAUSE
            override val minPause = BlueTts.MIN_PAUSE
            override fun parts(text: String, opt: BlueTts.Options, onProgress: (Float) -> Unit, retake: Boolean) = engine.synthesizeParts(text, v, opt, onProgress = onProgress)
        }
    }

    /**
     * Pieces -> one recording ([Joiner]): every sentence pause the same ([BlueTts.Options.silence], or the voice's
     * own when 0), edges trimmed without clipping, loudness evened out, peak-limited.
     */
    private fun join(parts: List<BlueTts.Part>, pause: Float, engine: Engine) =
        Joiner.join(parts.map { it.samples }, parts.map { it.text }, engine.sampleRate, pause, engine.naturalPause, floor = engine.minPause)

    /** A Qwen voice print from a recording (44.1 kHz mono), for "make a voice from a recording". */
    suspend fun qwenVoicePrint(wav44k: FloatArray): FloatArray = withContext(Dispatchers.Default) {
        // The first 20 s are plenty for a voice print and keep the encoder quick on long recordings.
        val clip = if (wav44k.size > 20 * 44100) wav44k.copyOf(20 * 44100) else wav44k
        lock.withLock { loadQwen { _, _ -> }.voicePrint(com.karianoot.engine.VoiceCloner.resample(clip, 44100, 24000)) }
    }

    suspend fun speak(text: String, voice: VoiceInfo, opt: BlueTts.Options, onStage: (String, Float) -> Unit): Creation =
        withContext(Dispatchers.Default) {
            lock.withLock {
                cancelled = false
                val t0 = System.nanoTime()
                val stage = guard(onStage)
                val engine = engineFor(voice, stage)
                stage("מקריא…", 0.02f)
                // The user's fixes first (they win), then the football-text cleanup (1X2, PSG, ת"א, scores).
                val parts = engine.parts(TextPrep.apply(Pronounce.apply(text, fixes())), opt, onProgress = { p -> stage("מקריא…", p) })
                val joined = join(parts, opt.silence, engine)
                val wav = joined.samples
                val spans = parts.mapIndexed { i, p -> PartSpan(p.text, joined.spans[i][0], joined.spans[i][1]) }
                val f = File(creationsDir, "karianoot_${System.currentTimeMillis()}.wav")
                Wav.write(f, wav, engine.sampleRate)
                val c = Creation(f, text, label(voice), wav.size.toFloat() / engine.sampleRate, System.currentTimeMillis(),
                    ((System.nanoTime() - t0) / 1e9).toFloat(), spans, voice.id, opt.steps, opt.speed, opt.temperature, joined.pauseSeconds)
                save(listOf(c) + history())
                recordSpeed(voice.modelId, c.genSeconds, c.seconds)
                c
            }
        }

    /** A short sample of [voice] (cached per voice), to choose voices by ear. */
    suspend fun preview(voice: VoiceInfo): File = withContext(Dispatchers.Default) {
        val f = File(ctx.cacheDir, "preview2_${voice.id}.wav")
        if (f.exists() && f.length() > 1000) return@withContext f
        lock.withLock {
            val engine = engineFor(voice) { _, _ -> }
            val opt = BlueTts.Options(steps = voice.steps[1], temperature = voice.liveliness)
            val parts = engine.parts(PREVIEW_TEXT, opt)
            Wav.write(f, join(parts, 0f, engine).samples, engine.sampleRate)
        }
        f
    }

    /** Makes sentence [i] of [c] again (a new take) and splices it into the recording. */
    suspend fun redoPart(c: Creation, i: Int, voice: VoiceInfo): Creation =
        withContext(Dispatchers.Default) {
            lock.withLock {
                val engine = engineFor(voice) { _, _ -> }
                val span = c.parts[i]
                val opt = BlueTts.Options(steps = c.steps, speed = c.speed, perSentence = false, peakLimit = null, temperature = c.temperature)
                val (old, rate) = Wav.read(c.file)
                // The retake gets the same sentence pauses as the recording and the loudness of the take it replaces.
                val ps = engine.parts(span.text, opt, retake = true)
                var fresh = Joiner.join(ps.map { it.samples }, ps.map { it.text }, rate, c.pause, engine.naturalPause, floor = engine.minPause).samples
                val target = Joiner.level(old.copyOfRange(span.start.coerceIn(0, old.size), span.end.coerceIn(0, old.size)), rate)
                val now = Joiner.level(fresh, rate)
                if (target > 0 && now > 0) {
                    val g = (target / now).toFloat().coerceIn(0.71f, 1.41f)
                    fresh = Wav.limitPeak(FloatArray(fresh.size) { fresh[it] * g })
                }
                val out = FloatArray(old.size - (span.end - span.start) + fresh.size)
                old.copyInto(out, 0, 0, span.start)
                fresh.copyInto(out, span.start)
                old.copyInto(out, span.start + fresh.size, span.end, old.size)
                val shift = fresh.size - (span.end - span.start)
                val parts = c.parts.mapIndexed { j, p ->
                    when {
                        j < i -> p
                        j == i -> p.copy(end = p.start + fresh.size)
                        else -> p.copy(start = p.start + shift, end = p.end + shift)
                    }
                }
                val f = File(creationsDir, "karianoot_${System.currentTimeMillis()}.wav")
                Wav.write(f, out, rate)
                val n = c.copy(file = f, parts = parts, seconds = out.size.toFloat() / rate)
                save(history().map { if (it.file == c.file) n else it })
                c.file.delete()
                n
            }
        }

    /** Copies a voice shipped in assets/ to a file (refreshed when the app is updated). */
    private fun assetFile(path: String): File {
        val f = File(ctx.filesDir, "builtin/" + path)
        val stamp = File(f.path + ".v")
        val ver = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
        if (!f.exists() || !stamp.exists() || stamp.readText() != ver) {
            f.parentFile?.mkdirs()
            ctx.assets.open(path).use { i -> f.outputStream().use { i.copyTo(it) } }
            stamp.writeText(ver)
        }
        return f
    }

    fun history(): List<Creation> = runCatching {
        val a = JSONArray(index.readText())
        (0 until a.length()).map { a.getJSONObject(it) }.mapNotNull { o ->
            val f = File(creationsDir, o.getString("file"))
            if (!f.exists()) null else Creation(f, o.getString("text"), o.getString("voice"),
                o.getDouble("seconds").toFloat(), o.getLong("at"), o.optDouble("gen", 0.0).toFloat(),
                o.optJSONArray("parts")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }
                    .map { PartSpan(it.getString("t"), it.getInt("s"), it.getInt("e")) } } ?: emptyList(),
                o.optString("vid"), o.optInt("steps", 10), o.optDouble("speed", 1.0).toFloat(), o.optDouble("temp", 1.0).toFloat(), o.optDouble("pause", 0.0).toFloat())
        }
    }.getOrDefault(emptyList())

    fun delete(c: Creation) { c.file.delete(); save(history().filter { it.file != c.file }) }

    private fun save(all: List<Creation>) {
        // Keep the newest [KEEP] recordings; older ones are deleted from the phone.
        val list = all.take(KEEP)
        all.drop(KEEP).forEach { it.file.delete() }
        val a = JSONArray()
        for (c in list) a.put(JSONObject().put("file", c.file.name).put("text", c.text).put("voice", c.voice)
            .put("seconds", c.seconds.toDouble()).put("at", c.createdAt).put("gen", c.genSeconds.toDouble())
            .put("vid", c.voiceId).put("steps", c.steps).put("speed", c.speed.toDouble()).put("temp", c.temperature.toDouble()).put("pause", c.pause.toDouble())
            .put("parts", JSONArray().also { pa -> c.parts.forEach { pa.put(JSONObject().put("t", it.text).put("s", it.start).put("e", it.end)) } }))
        index.writeText(a.toString())
    }

    /** Copies a creation into the phone's Music/Karianoot folder, where other apps can find it. */
    fun exportToMusic(c: Creation): Boolean = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, c.file.name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Karianoot")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        ctx.contentResolver.openOutputStream(uri)?.use { out -> c.file.inputStream().use { it.copyTo(out) } }
        true
    }.getOrDefault(false)

    companion object {
        const val KEEP = 30
        /** Work per second of audio before this phone has measured a model: Magpie measured 2.2 on a 4-core CI runner. */
        val FIRST_GUESS = mapOf(Catalog.magpie.id to 2.2f)
        const val PREVIEW_TEXT = "ערב טוב לכל האוהדים! ככה אני נשמע, והיחס הערב פשוט מטורף."
    }

    fun release() { tts?.close(); qwen?.close(); magpie?.close(); g2p?.close(); tts = null; qwen = null; magpie = null; g2p = null; ttsModel = null }
}
