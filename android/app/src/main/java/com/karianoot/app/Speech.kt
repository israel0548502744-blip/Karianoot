package com.karianoot.app

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import com.karianoot.engine.BlueTts
import com.karianoot.engine.BlueVoice
import com.karianoot.engine.HebrewG2P
import com.karianoot.engine.Joiner
import com.karianoot.engine.MagpieTts
import com.karianoot.engine.Master
import com.karianoot.engine.Script
import com.karianoot.engine.Sfx
import com.karianoot.engine.TimeStretch
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

/** Where one sentence sits in a recording (samples), so it can be made again on its own; [voice]: who said it (two-voice scripts). */
data class PartSpan(val text: String, val start: Int, val end: Int, val voice: String = "")

data class Creation(
    val file: File, val text: String, val voice: String, val seconds: Float, val createdAt: Long, val genSeconds: Float,
    val parts: List<PartSpan> = emptyList(), val voiceId: String = "", val steps: Int = 10, val speed: Float = 1f,
    val temperature: Float = 1f,
    /** The sentence pause used (seconds), so a retake of one sentence fits back in the same way. */
    val pause: Float = 0f,
    /** "סאונד אולפן" was applied ([Master.studio]); a retake of one sentence gets it too. */
    val studio: Boolean = false,
)

/** What "עריכת סאונד" does to a narration (a new copy is made). */
data class Polish(
    val music: android.net.Uri? = null, val level: Float = 0.15f, val duck: Boolean = true,
    val studio: Boolean = true, val intro: String? = null, val outro: String? = null,
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
        /** One piece of IPA as it is (for "איך זה ייקרא": hear one word). */
        fun ipa(ipa: String, opt: BlueTts.Options): FloatArray
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
                override fun ipa(ipa: String, opt: BlueTts.Options) = m.speakIpa(MagpieTts.clean(ipa), voice.speaker)
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
                override fun ipa(ipa: String, opt: BlueTts.Options) = q.speakIpa(ipa, spk)
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
            override fun ipa(ipa: String, opt: BlueTts.Options) = engine.synthesizePhonemes(ipa, v, opt)
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

    /** One stretch of speech rendered by one voice: samples at [rate], its sentences and its sentence pause. */
    private class Rendered(val samples: FloatArray, val spans: List<PartSpan>, val pause: Float, val rate: Int)

    /** [text] with its markers ({הפסקה 2}, {שריקה}) by one [engine]; [progress] gets 0..1 of this text. */
    private fun render(text: String, voiceId: String, engine: Engine, opt: BlueTts.Options, progress: (Float) -> Unit): Rendered {
        val segs = if (Script.hasMarkers(text)) Script.parse(text) else listOf(Script.Speech(text))
        val total = segs.sumOf { (it as? Script.Speech)?.text?.length ?: 0 }.coerceAtLeast(1)
        var done = 0
        // Each stretch of speech is spoken and joined on its own; markers go between them.
        val joined = segs.map { seg ->
            if (seg !is Script.Speech) null else {
                val from = done
                // The user's fixes first (they win), then the football-text cleanup (1X2, PSG, ת"א, scores).
                val ps = engine.parts(TextPrep.apply(Pronounce.apply(seg.text, fixes())), opt,
                    onProgress = { p -> progress((from + p * seg.text.length) / total) })
                done += seg.text.length
                ps to join(ps, opt.silence, engine)
            }
        }
        val rate = engine.sampleRate
        val pauseSec = joined.firstNotNullOfOrNull { it?.second?.pauseSeconds } ?: engine.naturalPause
        val out = ArrayList<FloatArray>()
        val spans = ArrayList<PartSpan>()
        var len = 0
        fun add(a: FloatArray) { out.add(a); len += a.size }
        fun gap(sec: Float) { if (sec > 0f) add(FloatArray((sec * rate).toInt())) }
        segs.forEachIndexed { k, seg ->
            when (seg) {
                is Script.Speech -> {
                    val (ps, j) = joined[k]!!
                    if (k > 0 && segs[k - 1] is Script.Speech) gap(pauseSec)
                    ps.forEachIndexed { i, p -> spans.add(PartSpan(p.text, len + j.spans[i][0], len + j.spans[i][1], voiceId)) }
                    add(j.samples)
                }
                is Script.Pause -> gap(seg.seconds)
                is Script.Effect -> {
                    if (len > 0) gap(pauseSec / 2)
                    add(Sfx.make(seg.name, rate))
                    if (k < segs.size - 1) gap(pauseSec / 2)
                }
            }
        }
        return Rendered(FloatArray(len).also { a -> var p = 0; for (x in out) { x.copyInto(a, p); p += x.size } }, spans, pauseSec, rate)
    }

    /**
     * [text] -> a saved recording. Markers in braces ({הפסקה 2}, {שריקה}...) become pauses and sound effects
     * ([Script]); lines marked "ב:" are said by [voice2] when it is given ([Script.turns]); [targetSeconds] > 0
     * stretches the result to that length (pitch kept); [studio] applies [Master.studio].
     */
    suspend fun speak(text: String, voice: VoiceInfo, opt: BlueTts.Options, targetSeconds: Float = 0f, studio: Boolean = false,
                      voice2: VoiceInfo? = null, onStage: (String, Float) -> Unit): Creation =
        withContext(Dispatchers.Default) {
            lock.withLock {
                cancelled = false
                val t0 = System.nanoTime()
                val stage = guard(onStage)
                val turns = if (voice2 != null && Script.isDialogue(text)) Script.turns(text) else listOf(1 to text)
                val total = turns.sumOf { it.second.length }.coerceAtLeast(1)
                var done = 0
                val rendered = turns.map { (who, t) ->
                    val v = if (who == 2) voice2!! else voice
                    val engine = engineFor(v, stage)
                    stage("מקריא…", done.toFloat() / total)
                    val from = done
                    render(t, v.id, engine, opt) { p -> stage("מקריא…", (from + p * t.length) / total) }.also { done += t.length }
                }
                // Everything at the first voice's rate; a change of speaker gets a slightly longer pause.
                val rate = rendered.first().rate
                val pauseSec = rendered.first().pause
                val spans = ArrayList<PartSpan>()
                val chunks = ArrayList<FloatArray>()
                var len = 0
                rendered.forEachIndexed { i, r ->
                    if (i > 0) { val g = FloatArray((pauseSec * 1.6f * rate).toInt()); chunks.add(g); len += g.size }
                    val k = rate.toDouble() / r.rate
                    val a = if (r.rate == rate) r.samples else com.karianoot.engine.VoiceCloner.resample(r.samples, r.rate, rate)
                    r.spans.forEach { spans.add(it.copy(start = len + (it.start * k).toInt(), end = len + (it.end * k).toInt())) }
                    chunks.add(a); len += a.size
                }
                var wav = FloatArray(len).also { a -> var p = 0; for (x in chunks) { x.copyInto(a, p); p += x.size } }
                var speed = opt.speed
                var parts: List<PartSpan> = spans
                // "אורך מדויק": stretch to the asked length (between 0.7x and 1.45x of the natural one).
                if (targetSeconds > 0f && wav.isNotEmpty()) {
                    val ratio = (wav.size.toFloat() / rate / targetSeconds).coerceIn(0.7f, 1.45f)
                    if (kotlin.math.abs(ratio - 1f) > 0.01f) {
                        wav = TimeStretch.stretch(wav, ratio, rate)
                        parts = spans.map { it.copy(start = (it.start / ratio).toInt(), end = (it.end / ratio).toInt().coerceAtMost(wav.size)) }
                        speed *= ratio
                    }
                }
                if (studio) wav = Master.studio(wav, rate)
                val f = File(creationsDir, "karianoot_${System.currentTimeMillis()}.wav")
                Wav.write(f, wav, rate)
                val name = if (rendered.size > 1 && voice2 != null) label(voice) + " + " + voice2.name else label(voice)
                val c = Creation(f, text, name, wav.size.toFloat() / rate, System.currentTimeMillis(),
                    ((System.nanoTime() - t0) / 1e9).toFloat(), parts, voice.id, opt.steps, speed, opt.temperature, pauseSec, studio)
                save(listOf(c) + history())
                if (rendered.size == 1) recordSpeed(voice.modelId, c.genSeconds, c.seconds)
                c
            }
        }

    /** One spoken word: what is shown, and the IPA the voice gets. */
    data class Reading(val word: String, val ipa: String)

    /**
     * "איך זה ייקרא": the text through the app's own path (the user's fixes, the football cleanup, the lexicon, the G2P),
     * per sentence, word by word. A sentence whose words cannot be paired with its IPA comes back as one item.
     */
    suspend fun readings(text: String): List<List<Reading>> = withContext(Dispatchers.Default) {
        lock.withLock {
            val g = loadG2p { _, _ -> }
            val clean = Script.MARKER.replace(text, " ")
            TextPrep.apply(Pronounce.apply(clean, fixes())).split(com.karianoot.engine.Readings.SENTENCES).map { it.trim() }.filter { it.isNotEmpty() }.map { s ->
                val ipa = BlueTts.toIpa(g, s)
                com.karianoot.engine.Readings.pair(s, ipa)?.map { (w, i) -> Reading(w, i) } ?: listOf(Reading(s, com.karianoot.engine.Readings.clean(ipa)))
            }
        }
    }

    /** IPA of a spelling the user typed ("כמו שזה נשמע" or with niqqud). */
    suspend fun ipaOf(text: String): String = withContext(Dispatchers.Default) {
        lock.withLock { com.karianoot.engine.Readings.clean(BlueTts.toIpa(loadG2p { _, _ -> }, text)) }
    }

    /** [ipa] said by [voice], as a playable file. */
    suspend fun sayIpa(ipa: String, voice: VoiceInfo): File = withContext(Dispatchers.Default) {
        lock.withLock {
            val engine = engineFor(voice) { _, _ -> }
            val a = engine.ipa(ipa + ".", BlueTts.Options(steps = voice.steps[1], temperature = voice.liveliness, perSentence = false))
            val f = File(ctx.cacheDir, "word_${ipa.hashCode()}_${voice.id}.wav")
            Wav.write(f, Wav.limitPeak(a), engine.sampleRate)
            f
        }
    }

    /** A short sample of [voice] (cached per voice), to choose voices by ear. */
    suspend fun preview(voice: VoiceInfo): File = withContext(Dispatchers.Default) {
        val f = File(ctx.cacheDir, "preview3_${voice.id}.wav")
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
                if (engine.sampleRate != rate) fresh = com.karianoot.engine.VoiceCloner.resample(fresh, engine.sampleRate, rate)
                if (c.studio) fresh = Master.studio(fresh, rate)
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
                    .map { PartSpan(it.getString("t"), it.getInt("s"), it.getInt("e"), it.optString("v")) } } ?: emptyList(),
                o.optString("vid"), o.optInt("steps", 10), o.optDouble("speed", 1.0).toFloat(), o.optDouble("temp", 1.0).toFloat(), o.optDouble("pause", 0.0).toFloat(),
                o.optBoolean("studio", false))
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
            .put("vid", c.voiceId).put("steps", c.steps).put("speed", c.speed.toDouble()).put("temp", c.temperature.toDouble()).put("pause", c.pause.toDouble()).put("studio", c.studio)
            .put("parts", JSONArray().also { pa -> c.parts.forEach { pa.put(JSONObject().put("t", it.text).put("s", it.start).put("e", it.end).put("v", it.voice)) } }))
        index.writeText(a.toString())
    }

    /**
     * "עריכת סאונד": a copy of [c] with studio sound, an effect before / after, and music underneath (ducked under the
     * words when [Polish.duck]); saved as a new recording at the top of the list.
     */
    suspend fun polish(c: Creation, p: Polish): Creation = withContext(Dispatchers.Default) {
        var (voice, rate) = Wav.read(c.file)
        if (p.studio && !c.studio) voice = Master.studio(voice, rate)
        fun cat(vararg a: FloatArray): FloatArray { val o = FloatArray(a.sumOf { it.size }); var k = 0; for (x in a) { x.copyInto(o, k); k += x.size }; return o }
        var shift = 0
        p.intro?.let { val e = Sfx.make(it, rate); shift = e.size + (0.25f * rate).toInt(); voice = cat(e, FloatArray((0.25f * rate).toInt()), voice) }
        p.outro?.let { voice = cat(voice, FloatArray((0.3f * rate).toInt()), Sfx.make(it, rate)) }
        var out = voice
        if (p.music != null) {
            val m = AudioDecoder.decode(ctx, p.music, maxSeconds = 90)   // loops if the narration is longer
            out = com.karianoot.engine.Mixer.mix(voice, rate, m, 44100, p.level, duck = p.duck)
        }
        if (p.studio) out = Master.limit(out, rate)
        val what = listOfNotNull("אולפן".takeIf { p.studio }, "מוזיקה".takeIf { p.music != null }, "אפקטים".takeIf { p.intro != null || p.outro != null })
        val f = File(creationsDir, c.file.nameWithoutExtension + "_mix_${System.currentTimeMillis() % 100000}.wav")
        Wav.write(f, out, rate)
        val n = c.copy(file = f, voice = c.voice + " · " + what.joinToString(" + "), seconds = out.size.toFloat() / rate,
            // Not re-made sentence by sentence (voiceId empty), but the sentence times stay for captions.
            createdAt = System.currentTimeMillis(), genSeconds = 0f, parts = c.parts.map { it.copy(start = it.start + shift, end = it.end + shift) }, voiceId = "", studio = c.studio || p.studio)
        save(listOf(n) + history())
        n
    }

    /** One sentence of a narration as the recognizer heard it, and the words it missed. */
    data class Check(val index: Int, val text: String, val heard: String, val missed: List<com.karianoot.engine.Proofread.Miss>)

    /**
     * "בדיקת הקריינות": every sentence of [c] through the Hebrew recognizer on the phone (whisper.cpp + ivrit-ai), compared
     * with what it should say (as tools/proof does on CI). The recognizer is loaded for the check and freed after it.
     */
    suspend fun check(c: Creation, onProgress: (Float) -> Unit): List<Check> = withContext(Dispatchers.Default) {
        lock.withLock {
            // One big model in memory at a time.
            tts?.close(); tts = null; ttsModel = null; qwen?.close(); qwen = null; magpie?.close(); magpie = null
            val (audio, rate) = Wav.read(c.file)
            val parts = c.parts.ifEmpty { listOf(PartSpan(c.text, 0, audio.size)) }
            com.karianoot.engine.WhisperAsr(File(models.dir(Catalog.proof), Catalog.PROOF_FILE).path,
                threads = Runtime.getRuntime().availableProcessors().coerceIn(4, 8)).use { asr ->
                parts.mapIndexed { i, p ->
                    onProgress(i.toFloat() / parts.size)
                    val a = p.start.coerceIn(0, audio.size); val b = p.end.coerceIn(a, audio.size)
                    val heard = asr.hear(audio.copyOfRange(a, b), rate)
                    val said = com.karianoot.engine.HebrewNumbers.normalize(com.karianoot.engine.Captions.display(p.text))
                    Check(i, p.text, heard, com.karianoot.engine.Proofread.missed(said, heard))
                }.also { onProgress(1f) }
            }
        }
    }

    /** Word-by-word captions of [c] (from its sentence times; the whole text over the whole file when there are none). */
    fun captions(c: Creation): List<com.karianoot.engine.Captions.Card> {
        val (audio, rate) = Wav.read(c.file)
        val spans = c.parts.map { com.karianoot.engine.Captions.Span(it.text, it.start, it.end) }
            .ifEmpty { listOf(com.karianoot.engine.Captions.Span(c.text, 0, audio.size)) }
        return com.karianoot.engine.Captions.cards(com.karianoot.engine.Captions.words(spans, audio, rate))
    }

    val videosDir get() = File(creationsDir, "videos").apply { mkdirs() }

    /** The .srt captions of [c] as a file to share. */
    fun srtFile(c: Creation): File = File(videosDir, c.file.nameWithoutExtension + ".srt").apply {
        writeText(com.karianoot.engine.Captions.srt(captions(c)))
    }

    /** Saves a video into the phone's Movies/Karianoot folder (the gallery). */
    fun exportVideo(f: File): Boolean = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Karianoot")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
        true
    }.getOrDefault(false)

    /** A sound effect as a playable file (cached), to hear it before putting it in. */
    fun effectSample(name: String): File {
        val f = File(ctx.cacheDir, "sfx1_${Sfx.NAMES[name] ?: "x"}.wav")
        if (!f.exists()) Wav.write(f, Sfx.make(name, 44100), 44100)
        return f
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
        const val PREVIEW_TEXT = "תכירו את ישראל, סוכן הנסיעות הטוב בעולם!"
    }

    fun release() { tts?.close(); qwen?.close(); magpie?.close(); g2p?.close(); tts = null; qwen = null; magpie = null; g2p = null; ttsModel = null }
}
