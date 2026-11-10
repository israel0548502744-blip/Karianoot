package com.karianoot.engine

/**
 * JNI bridge to magpie-tts.cpp (android/magpie/magpie_jni.cpp): Magpie TTS Multilingual 357M with its Hebrew fine-tune
 * (NVIDIA Open Model License), one GGUF, on the CPU. The library is libmagpiejni.so (built in CI for arm64-v8a, and for
 * the host in magpie-jni.yml).
 */
object MagpieNative {
    @Volatile private var loaded = false

    /** Loads libmagpiejni from the app (no argument) or from an absolute path (JVM tests). */
    @Synchronized fun loadLibrary(path: String? = null) {
        if (loaded) return
        if (path != null) System.load(path) else System.loadLibrary("magpiejni")
        loaded = true
    }

    /** GGUF -> handle; throws with the reason on failure. */
    @JvmStatic external fun load(gguf: String): Long
    @JvmStatic external fun free(handle: Long)
    @JvmStatic external fun sampleRate(handle: Long): Int
    /** Stops the running [synthesize] within a decoder step (it throws CancellationException). */
    @JvmStatic external fun cancel()
    /**
     * IPA -> mono samples at [sampleRate]. Negative temperature/topk/cfgScale/maxFrames = the model's defaults,
     * cfgScale 0 = no guidance, seed <= 0 = random, threads <= 0 = automatic.
     */
    @JvmStatic external fun synthesize(handle: Long, ipa: String, language: String, speaker: Int, temperature: Float, topk: Int,
                                       cfgScale: Float, seed: Long, threads: Int, maxFrames: Int): FloatArray
}

/**
 * Magpie Hebrew: text -> speech in one of the model's baked voices (speaker index 5-19). Text goes through the same
 * Hebrew G2P as Blue and Qwen (stressed IPA), in pieces of 2-3 sentences; a broken piece ([TakeCheck.MAGPIE]) is made
 * again with another seed.
 */
class MagpieTts(gguf: String, private val g2p: HebrewG2P?, private val threads: Int = 0) : AutoCloseable {
    private val h: Long
    val sampleRate: Int

    init {
        MagpieNative.loadLibrary(System.getProperty("karianoot.magpiejni"))
        h = MagpieNative.load(gguf)
        sampleRate = MagpieNative.sampleRate(h)
    }

    /** Hebrew -> the IPA the model reads (lexicon, G2P, fixes), limited to the symbols it was trained on. */
    fun ipa(text: String): String {
        val t = BlueTts.stripEmoji(text)
        return clean(if (t.any { it.code in 0x0590..0x05FF }) BlueTts.toIpa(g2p!!, t) else t)
    }

    /** One piece of IPA -> samples. [seed] <= 0: a random take. */
    fun speakIpa(ipa: String, speaker: Int, seed: Long = -1, temperature: Float = TEMPERATURE, topk: Int = TOP_K,
                 cfgScale: Float = CFG_SCALE): FloatArray =
        MagpieNative.synthesize(h, ipa, LANGUAGE, speaker, temperature, topk, cfgScale, seed, threads, maxFrames(ipa))

    fun cancel() = MagpieNative.cancel()

    /**
     * The text in pieces ([pieces]), each spoken on its own; a piece that comes out broken (ran on, stalled, dropped
     * words) is made again with a new seed, up to [retakes] times, keeping the take closest to a normal one.
     * [seed] > 0 makes every take reproducible; [cancelled] is asked before each take.
     */
    fun synthesizeParts(text: String, speaker: Int, onProgress: (Float) -> Unit = {}, retakes: Int = 2, seed: Long = -1,
                        cancelled: () -> Boolean = { false }): List<BlueTts.Part> {
        val list = pieces(text)
        return list.mapIndexed { i, piece ->
            val p = ipa(piece)
            fun take(t: Int): FloatArray {
                if (cancelled()) throw java.util.concurrent.CancellationException("cancelled")
                return speakIpa(p, speaker, if (seed > 0) seed + 1000L * t + i else -1)
            }
            var best = take(0)
            var report = TakeCheck.measure(best, sampleRate, p.length, TakeCheck.MAGPIE)
            var tries = 0
            while (report.broken && tries < retakes) {
                tries++
                val alt = take(tries)
                val r = TakeCheck.measure(alt, sampleRate, p.length, TakeCheck.MAGPIE)
                if (r.badness < report.badness) { best = alt; report = r }
            }
            BlueTts.Part(piece, best).also { onProgress((i + 1f) / list.size) }
        }
    }

    override fun close() = MagpieNative.free(h)

    companion object {
        const val LANGUAGE = "he"
        const val TEMPERATURE = 0.7f
        const val TOP_K = 80
        const val CFG_SCALE = 2.5f
        /** Magpie's own sentence-end pause (median 0.32 s measured), when a text has none to measure. */
        const val NATURAL_PAUSE = 0.32f
        /** The shortest sentence pause used between pieces and sentences. */
        const val MIN_PAUSE = 0.25f
        /** The model's frame limit (500 codec frames, ~23 s): its decoder cache is sized for it. */
        const val MODEL_MAX_FRAMES = 500
        /** A sentence longer than this is cut at commas (or between words) so no piece runs too long. */
        const val MAX_PIECE_CHARS = 180

        /** The symbols the Hebrew tokenizer knows; anything else is mapped out. */
        const val SYMBOLS = "abdefhijklmnoprstuvzɡʁʃʔχˈ,.?!"
        private val MAP = mapOf('g' to "ɡ", 'r' to "ʁ", 'x' to "χ", 'ʒ' to "ʒ", ';' to ",", ':' to ",", '…' to ".", '\'' to "ˈ")

        fun clean(ipa: String): String {
            val sb = StringBuilder(ipa.length)
            for (c in java.text.Normalizer.normalize(ipa, java.text.Normalizer.Form.NFC)) {
                when {
                    c in SYMBOLS -> sb.append(c)
                    c.isWhitespace() || c == '-' || c == '–' || c == '—' -> sb.append(' ')
                    else -> MAP[c]?.let { m -> if (m.all { it in SYMBOLS }) sb.append(m) }
                }
            }
            return sb.toString().replace(Regex(" +([,.?!])"), "$1").replace(Regex("\\s+"), " ").trim()
        }

        /** Frame budget: ~1.1 frames per IPA character of speech; twice that plus 3 s, within the model's limit. */
        fun maxFrames(ipa: String) = (64 + ipa.length * 2.2f).toInt().coerceIn(64, MODEL_MAX_FRAMES)

        /** Pieces of 2-3 sentences (as Blue and Qwen); an over-long sentence is cut at commas, else between words. */
        fun pieces(text: String): List<String> =
            BlueTts.chunkText(BlueTts.stripEmoji(text), BlueTts.CHUNK_CHARS).flatMap { splitLong(it) }

        private fun splitLong(piece: String): List<String> {
            if (piece.length <= MAX_PIECE_CHARS) return listOf(piece)
            val out = ArrayList<String>()
            var cur = ""
            fun flush() { if (cur.isNotBlank()) out.add(cur.trim()); cur = "" }
            for (clause in piece.split(Regex("(?<=[,;:])\\s+"))) {
                val words = if (clause.length > MAX_PIECE_CHARS) clause.split(' ') else listOf(clause)
                for (w in words) {
                    if (cur.isNotEmpty() && cur.length + 1 + w.length > BlueTts.CHUNK_CHARS) flush()
                    cur = if (cur.isEmpty()) w else "$cur $w"
                }
            }
            flush()
            return out
        }
    }
}
