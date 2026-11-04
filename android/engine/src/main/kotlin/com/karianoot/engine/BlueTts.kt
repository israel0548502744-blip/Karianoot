package com.karianoot.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import java.util.Random

/** A Blue voice: the two style tensors from a voices/<name>.json file. */
class BlueVoice(val ttl: NpArray, val dp: NpArray) {
    companion object {
        fun load(file: File): BlueVoice {
            val o = JSONObject(file.readText())
            return BlueVoice(tensor(o.getJSONObject("style_ttl")), tensor(o.getJSONObject("style_dp")))
        }

        /** Average several voices into one speaker (as BlueTTS does with a list of JSONs). */
        fun mix(voices: List<BlueVoice>, weights: List<Float> = List(voices.size) { 1f }): BlueVoice {
            val w = weights.sum()
            fun avg(sel: (BlueVoice) -> NpArray): NpArray {
                val first = sel(voices[0])
                val out = FloatArray(first.size)
                voices.forEachIndexed { i, v -> val d = sel(v).data; for (j in out.indices) out[j] += d[j] * weights[i] / w }
                return NpArray(first.shape, out)
            }
            return BlueVoice(avg { it.ttl }, avg { it.dp })
        }

        private fun tensor(o: JSONObject): NpArray {
            val dims = o.getJSONArray("dims").let { a -> IntArray(a.length()) { a.getInt(it) } }
            val flat = ArrayList<Float>(dims.fold(1) { a, b -> a * b })
            fun walk(x: Any) { if (x is JSONArray) for (i in 0 until x.length()) walk(x.get(i)) else flat.add((x as Number).toFloat()) }
            walk(o.get("data"))
            return NpArray(dims, flat.toFloatArray())
        }
    }
}

/**
 * BlueTTS (flow matching, 44.1 kHz) — a Kotlin port of `blue_onnx.TextToSpeech`.
 * Works with the BlueTTS 2.5 and Blue V3 ONNX bundles (detected from the graphs).
 *
 * @param dir folder with text_encoder / vector_estimator / vocoder / duration_predictor(_style).onnx,
 *            tts.json and optionally vocab.json, stats.npz, uncond.npz.
 */
class BlueTts(
    private val dir: File,
    private val g2p: HebrewG2P?,
    sessionOptions: () -> OrtSession.SessionOptions = { defaultOptions() },
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment(),
) : AutoCloseable {

    class Options(
        val steps: Int = 5,
        val cfgScale: Float = 4.0f,
        val speed: Float = 1.0f,
        /** Seconds of silence between sentences. */
        val silence: Float = 0.0f,
        val peakLimit: Float? = 0.95f,
    )

    fun interface Noise { fun sample(shape: IntArray): FloatArray }

    private val cfg = JSONObject(File(dir, "tts.json").readText())
    val sampleRate: Int = cfg.getJSONObject("ae").getInt("sample_rate")
    private val baseChunk = cfg.getJSONObject("ae").getInt("base_chunk_size")
    private val compress = cfg.getJSONObject("ttl").getInt("chunk_compress_factor")
    private val ldim = cfg.getJSONObject("ttl").getInt("latent_dim")

    private val dp: OrtSession
    private val textEnc: OrtSession
    private val vectorEst: OrtSession
    private val vocoder: OrtSession
    private val vocab: Map<String, Int>
    private val padId: Int
    private val vfHasCfg: Boolean
    private val vocoderChannels: Int?
    private val uText: NpArray?
    private val uRef: NpArray?
    private val latentMean: FloatArray?
    private val latentStd: FloatArray?
    private val normScale: Float

    init {
        val dpFile = File(dir, "duration_predictor_style.onnx").takeIf { it.exists() } ?: File(dir, "duration_predictor.onnx")
        dp = env.createSession(dpFile.path, sessionOptions())
        textEnc = env.createSession(File(dir, "text_encoder.onnx").path, sessionOptions())
        vectorEst = env.createSession(File(dir, "vector_estimator.onnx").path, sessionOptions())
        vocoder = env.createSession(File(dir, "vocoder.onnx").path, sessionOptions())

        val vocabJson = File(dir, "vocab.json").takeIf { it.exists() }?.readText()
            ?: BlueTts::class.java.getResourceAsStream("blue_vocab.json")!!.bufferedReader().readText()
        val v = JSONObject(vocabJson)
        padId = v.optInt("pad_id", 0)
        val c2i = v.getJSONObject("char_to_id")
        vocab = c2i.keys().asSequence().associateWith { c2i.getInt(it) }

        vfHasCfg = "cfg_scale" in vectorEst.inputNames
        val vocIn = (vocoder.inputInfo.values.first().info as TensorInfo).shape
        vocoderChannels = vocIn.getOrNull(1)?.takeIf { it > 0 }?.toInt()

        val uncond = File(dir, "uncond.npz").takeIf { it.exists() }?.let { Npy.readNpz(it) }
        uText = uncond?.get("u_text"); uRef = uncond?.get("u_ref")
        val stats = File(dir, "stats.npz").takeIf { it.exists() }?.let { Npy.readNpz(it) }
        latentMean = stats?.get("mean")?.data
        latentStd = stats?.get("std")?.data
        normScale = stats?.get("normalizer_scale")?.data?.firstOrNull()?.takeIf { it != 0f } ?: 1f
    }

    /** Hebrew text -> audio samples (mono float, [sampleRate] Hz). */
    fun synthesize(text: String, voice: BlueVoice, opt: Options = Options(), noise: Noise = gaussian(),
                   onProgress: (Float) -> Unit = {}): FloatArray {
        val cleaned = stripEmoji(text)
        val ipa = if (cleaned.any { it.code in 0x0590..0x05FF }) g2p!!.phonemize(cleaned) else cleaned
        return synthesizePhonemes(ipa, voice, opt, noise, onProgress)
    }

    fun synthesizePhonemes(ipa: String, voice: BlueVoice, opt: Options = Options(), noise: Noise = gaussian(),
                           onProgress: (Float) -> Unit = {}): FloatArray {
        val chunks = chunkText(ipa.replace(Regex("\\s+"), " ").trim(), 300)
        val parts = ArrayList<FloatArray>()
        chunks.forEachIndexed { i, c ->
            if (i > 0 && opt.silence > 0f) parts.add(FloatArray((opt.silence * sampleRate).toInt()))
            parts.add(infer(c, voice, opt, noise).first)
            onProgress((i + 1f) / chunks.size)
        }
        val out = FloatArray(parts.sumOf { it.size })
        var p = 0
        for (a in parts) { a.copyInto(out, p); p += a.size }
        return opt.peakLimit?.let { Wav.limitPeak(out, it) } ?: out
    }

    /** One chunk of phonemes -> (samples, predicted seconds). Mirrors `TextToSpeech._infer`, batch 1. */
    fun infer(phonemes: String, voice: BlueVoice, opt: Options, noise: Noise): Pair<FloatArray, Float> {
        val ids = encode(preprocess(phonemes))
        val L = ids.size
        val ttl = voice.ttl; val sdp = voice.dp
        val open = ArrayList<OnnxTensor>()
        fun f(data: FloatArray, vararg shape: Int) = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape.map { it.toLong() }.toLongArray()).also { open.add(it) }
        try {
            val textIds = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, L.toLong())).also { open.add(it) }
            val textMask = f(FloatArray(L) { 1f }, 1, 1, L)
            val styleTtl = f(ttl.data, *ttl.shape)
            val styleDp = f(sdp.data, *sdp.shape)

            var dur = dp.run(mapOf("text_ids" to textIds, "style_dp" to styleDp, "text_mask" to textMask)).use { r ->
                (r.get(0) as OnnxTensor).floatBuffer.get(0)
            }
            dur /= maxOf(opt.speed, 1e-6f)

            val (textEmb, textEmbShape) = textEnc.run(mapOf("text_ids" to textIds, "style_ttl" to styleTtl, "text_mask" to textMask)).use { r ->
                val t = r.get(0) as OnnxTensor
                floats(t) to t.info.shape.map { it.toInt() }.toIntArray()
            }
            val textEmbT = f(textEmb, *textEmbShape)

            val frameLen = baseChunk * compress
            val wavLen = (dur * sampleRate).toLong()
            val latentLen = maxOf(0L, ((dur.toDouble() * sampleRate).toLong() + frameLen - 1) / frameLen).toInt()
            val maskLen = ((wavLen + frameLen - 1) / frameLen).toInt()
            val latentDim = ldim * compress
            val mask = FloatArray(latentLen) { if (it < maskLen) 1f else 0f }
            var xt = noise.sample(intArrayOf(1, latentDim, latentLen))
            for (ch in 0 until latentDim) for (t in 0 until latentLen) xt[ch * latentLen + t] *= mask[t]
            val latentMask = f(mask, 1, 1, latentLen)
            val totalStep = f(floatArrayOf(opt.steps.toFloat()), 1)

            val useCfg = opt.cfgScale != 1f && uText != null && uRef != null
            val uTextT = if (!vfHasCfg && useCfg) f(uText!!.data, *uText.shape) else null
            val uRefT = if (!vfHasCfg && useCfg) f(uRef!!.data, *uRef.shape) else null
            val uMask = if (!vfHasCfg && useCfg) f(floatArrayOf(1f), 1, 1, 1) else null
            val cfgT = if (vfHasCfg) f(floatArrayOf(opt.cfgScale), 1) else null

            for (step in 0 until opt.steps) {
                OnnxTensor.createTensor(env, FloatBuffer.wrap(xt), longArrayOf(1, latentDim.toLong(), latentLen.toLong())).use { xtT ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(step.toFloat())), longArrayOf(1)).use { cur ->
                        val cond = hashMapOf<String, OnnxTensor>(
                            "noisy_latent" to xtT, "text_emb" to textEmbT, "style_ttl" to styleTtl,
                            "text_mask" to textMask, "latent_mask" to latentMask,
                            "current_step" to cur, "total_step" to totalStep,
                        )
                        if (cfgT != null) cond["cfg_scale"] = cfgT
                        val vCond = vectorEst.run(cond).use { floats(it.get(0) as OnnxTensor) }
                        xt = if (uTextT != null) {
                            val uncond = hashMapOf<String, OnnxTensor>(
                                "noisy_latent" to xtT, "text_emb" to uTextT, "style_ttl" to uRefT!!,
                                "text_mask" to uMask!!, "latent_mask" to latentMask,
                                "current_step" to cur, "total_step" to totalStep,
                            )
                            val vUn = vectorEst.run(uncond).use { floats(it.get(0) as OnnxTensor) }
                            FloatArray(vCond.size) { vUn[it] + opt.cfgScale * (vCond[it] - vUn[it]) }
                        } else vCond
                    }
                }
            }

            val vocIn = vocoderLatent(xt, latentDim, latentLen)
            var wav = vocoder.run(mapOf(vocoder.inputNames.first() to f(vocIn.data, *vocIn.shape))).use { floats(it.get(0) as OnnxTensor) }
            if (wav.size > 2 * frameLen) wav = wav.copyOfRange(frameLen, wav.size - frameLen)
            return wav to dur
        } finally {
            open.forEach { it.close() }
        }
    }

    /** The vocoder either takes the flow output as-is, or de-normalized and decompressed (`_prepare_vocoder_latent`). */
    private fun vocoderLatent(xt: FloatArray, channels: Int, t: Int): NpArray {
        if (vocoderChannels != ldim) return NpArray(intArrayOf(1, channels, t), xt)
        val mean = latentMean ?: error("vocoder needs stats.npz")
        val std = latentStd!!
        val out = FloatArray(xt.size)
        // z[ch][t] = xt/scale*std[ch]+mean[ch]; ch = l*compress + f -> out[l][t*compress + f]
        for (l in 0 until ldim) for (fi in 0 until compress) {
            val ch = l * compress + fi
            val m = if (mean.size == 1) mean[0] else mean[ch]
            val s = if (std.size == 1) std[0] else std[ch]
            for (ti in 0 until t) {
                out[l * (t * compress) + ti * compress + fi] = (xt[ch * t + ti] / normScale) * s + m
            }
        }
        return NpArray(intArrayOf(1, ldim, t * compress), out)
    }

    /** `UnicodeProcessor._preprocess_text` for a phoneme string, tags already stripped. */
    fun preprocess(input: String): String {
        var text = input.replace(Regex("</?\\w+>"), "").replace(Regex("\\s+"), " ").trim()
        text = Normalizer.normalize(text, Normalizer.Form.NFKD)
        text = stripEmoji(text)
        val rep = linkedMapOf(
            "–" to "-", "‑" to "-", "—" to "-", "_" to " ",
            "“" to "\"", "”" to "\"", "‘" to "'", "’" to "'",
            "´" to "'", "`" to "'", "[" to " ", "]" to " ", "|" to " ", "/" to " ", "#" to " ", "→" to " ", "←" to " ",
        )
        for ((k, v) in rep) text = text.replace(k, v)
        text = text.replace(Regex("[♥☆♡©\\\\]"), "")
        for ((k, v) in linkedMapOf("@" to " at ", "e.g.," to "for example, ", "i.e.," to "that is, ")) text = text.replace(k, v)
        for (p in listOf(",", ".", "!", "?", ";", ":", "'")) text = text.replace(" $p", p)
        while ("\"\"" in text) text = text.replace("\"\"", "\"")
        while ("''" in text) text = text.replace("''", "'")
        while ("``" in text) text = text.replace("``", "`")
        text = text.replace(Regex("\\s+"), " ").trim()
        if (!Regex("[.!?;:,'\"')\\]}…。」』】〉》›»]$").containsMatchIn(text)) text += "."
        return text
    }

    fun encode(text: String): LongArray =
        HebrewG2P.codePoints(text).map { (vocab[it] ?: padId).toLong() }.toLongArray()

    override fun close() { dp.close(); textEnc.close(); vectorEst.close(); vocoder.close() }

    companion object {
        fun defaultOptions() = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(minOf(8, Runtime.getRuntime().availableProcessors()))
            setInterOpNumThreads(1)
        }

        fun gaussian(seed: Long? = null): Noise {
            val r = if (seed != null) Random(seed) else Random()
            return Noise { shape -> FloatArray(shape.fold(1) { a, b -> a * b }) { r.nextGaussian().toFloat() } }
        }

        private fun floats(t: OnnxTensor): FloatArray {
            val b = t.floatBuffer
            return FloatArray(b.remaining()).also { b.get(it) }
        }

        private val EMOJI = Regex(
            "[\\x{1F600}-\\x{1F64F}\\x{1F300}-\\x{1F5FF}\\x{1F680}-\\x{1F6FF}\\x{1F700}-\\x{1F77F}" +
                "\\x{1F780}-\\x{1F7FF}\\x{1F800}-\\x{1F8FF}\\x{1F900}-\\x{1F9FF}\\x{1FA00}-\\x{1FA6F}" +
                "\\x{1FA70}-\\x{1FAFF}☀-⛿✀-➿\\x{1F1E6}-\\x{1F1FF}]+"
        )

        fun stripEmoji(text: String) = EMOJI.replace(text, " ").replace(Regex("\\s+"), " ").trim()

        private val SENTENCE_SPLIT = Regex(
            "(?<!Mr\\.)(?<!Mrs\\.)(?<!Ms\\.)(?<!Dr\\.)(?<!Prof\\.)(?<!Sr\\.)(?<!Jr\\.)(?<!Ph\\.D\\.)(?<!etc\\.)" +
                "(?<!e\\.g\\.)(?<!i\\.e\\.)(?<!vs\\.)(?<!Inc\\.)(?<!Ltd\\.)(?<!Co\\.)(?<!Corp\\.)(?<!St\\.)(?<!Ave\\.)" +
                "(?<!Blvd\\.)(?<!\\b[A-Z]\\.)(?<=[.!?])\\s+"
        )

        /** `blue_onnx.chunk_text`: paragraphs, then sentences packed up to [maxLen] characters. */
        fun chunkText(text: String, maxLen: Int = 300): List<String> {
            val chunks = ArrayList<String>()
            for (para in text.trim().split(Regex("\\n\\s*\\n+")).map { it.trim() }.filter { it.isNotEmpty() }) {
                var cur = ""
                for (s in para.split(SENTENCE_SPLIT)) {
                    if (cur.length + s.length + 1 <= maxLen) cur += (if (cur.isNotEmpty()) " " else "") + s
                    else { if (cur.isNotEmpty()) chunks.add(cur.trim()); cur = s }
                }
                if (cur.isNotEmpty()) chunks.add(cur.trim())
            }
            return chunks
        }
    }
}
