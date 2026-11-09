package com.karianoot.engine

/**
 * JNI bridge to qwentts.cpp (android/qwen/qwen_jni.cpp): QwenTTS-he-1.7B (Apache-2.0) in GGUF, on the CPU.
 * The library is libqwenjni.so (built in CI for arm64-v8a, and for the host in the qwen test workflow).
 */
object QwenNative {
    @Volatile private var loaded = false

    /** Loads libqwenjni from the app (no argument) or from an absolute path (JVM tests). */
    @Synchronized fun load(path: String? = null) {
        if (loaded) return
        if (path != null) System.load(path) else System.loadLibrary("qwenjni")
        loaded = true
    }

    @JvmStatic external fun init(talker: String, codec: String, threads: Int): Long
    @JvmStatic external fun free(handle: Long)
    @JvmStatic external fun lastError(): String
    @JvmStatic external fun cancel()
    /** Speaker embedding ("voice print") from 24 kHz mono audio, or null. */
    @JvmStatic external fun voicePrint(handle: Long, audio24k: FloatArray): FloatArray?
    /** Stressed IPA -> 24 kHz mono samples in the voice of [voicePrint]; temperature 0 = greedy. */
    @JvmStatic external fun synthesize(handle: Long, ipa: String, voicePrint: FloatArray, temperature: Float, seed: Long, maxFrames: Int): FloatArray?
    @JvmStatic external fun numCodebooks(handle: Long): Int
    /** Style reference codes ([K, T] row-major) of 24 kHz mono audio, or null. */
    @JvmStatic external fun styleCodes(handle: Long, audio24k: FloatArray): IntArray?
    /** "Full clone": voice print + style codes + the reference's IPA transcript (Qwen ICL mode). */
    @JvmStatic external fun synthesizeStyled(handle: Long, ipa: String, voicePrint: FloatArray, codes: IntArray, refIpa: String,
                                             temperature: Float, seed: Long, maxFrames: Int): FloatArray?
}

/**
 * QwenTTS-he: Hebrew text -> speech in a cloned voice. Text goes through the same Hebrew G2P as Blue
 * (the model was trained on RenikudPlus stressed IPA), in pieces of 2-3 sentences.
 */
class QwenTts(talker: String, codec: String, private val g2p: HebrewG2P?, threads: Int = 0) : AutoCloseable {
    private val h: Long
    val sampleRate = 24000

    init {
        QwenNative.load(System.getProperty("karianoot.qwenjni"))
        h = QwenNative.init(talker, codec, threads)
        require(h != 0L) { "Qwen: " + QwenNative.lastError() }
    }

    fun voicePrint(audio24k: FloatArray): FloatArray =
        QwenNative.voicePrint(h, audio24k) ?: error("Qwen voice print: " + QwenNative.lastError())

    fun ipa(text: String): String {
        val t = BlueTts.stripEmoji(text)
        return if (t.any { it.code in 0x0590..0x05FF }) BlueTts.fixIpa(g2p!!.phonemize(t)) else t
    }

    /** One piece of text (already IPA) -> samples. */
    fun speakIpa(ipa: String, voice: FloatArray, temperature: Float = 0f, seed: Long = -1): FloatArray =
        QwenNative.synthesize(h, ipa, voice, temperature, seed, maxFrames(ipa)) ?: error("Qwen: " + QwenNative.lastError())

    /** Codebooks of the loaded codec (for [readRvq]). */
    val numCodebooks: Int get() = QwenNative.numCodebooks(h)

    /** A style reference (the recording itself, as codec codes) for [speakStyled]. */
    fun styleCodes(audio24k: FloatArray): IntArray = QwenNative.styleCodes(h, audio24k) ?: error("Qwen style: " + QwenNative.lastError())

    /** Speaks in the voice AND speaking style of a reference recording whose IPA transcript is [refIpa]. */
    fun speakStyled(ipa: String, voice: FloatArray, codes: IntArray, refIpa: String, temperature: Float = 0f, seed: Long = -1): FloatArray =
        QwenNative.synthesizeStyled(h, ipa, voice, codes, refIpa, temperature, seed, maxFrames(ipa)) ?: error("Qwen: " + QwenNative.lastError())

    /** Stops the running [speakIpa] (it then fails with "cancelled"). */
    fun cancel() = QwenNative.cancel()

    /** The text in pieces of 2-3 sentences, each spoken on its own (same split as Blue). */
    fun synthesizeParts(text: String, voice: FloatArray, temperature: Float = 0f, onProgress: (Float) -> Unit = {}): List<BlueTts.Part> {
        val pieces = BlueTts.chunkText(BlueTts.stripEmoji(text), BlueTts.CHUNK_CHARS)
        return pieces.mapIndexed { i, piece ->
            BlueTts.Part(piece, BlueTts.fadeEdges(speakIpa(ipa(piece), voice, temperature), sampleRate)).also { onProgress((i + 1f) / pieces.size) }
        }
    }

    override fun close() = QwenNative.free(h)

    companion object {
        /**
         * Frame budget for a piece: speech runs ~13 IPA characters per second and Qwen makes 12.5 frames per
         * second, so about 1 frame per character; twice that plus 3 seconds is generous but bounded.
         */
        fun maxFrames(ipa: String) = (40 + ipa.length * 2).coerceAtMost(1500)

        /** A voice print saved as raw little-endian float32 (.spk, as qwen-codec writes it). */
        fun readSpk(bytes: ByteArray): FloatArray {
            val b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.size / 4) { b.getFloat(it * 4) }
        }
        /**
         * Style codes from a .rvq file as qwen-codec writes it: [K, T] row-major, packed at 11 bits per code, LSB-first,
         * no header (qwentts.cpp rvq-file.h). [k] is the codec's codebook count ([QwenNative.numCodebooks]).
         */
        fun readRvq(bytes: ByteArray, k: Int, codeBits: Int = 11): IntArray {
            val n = ((bytes.size.toLong() * 8) / (k.toLong() * codeBits)).toInt() * k
            val mask = (1L shl codeBits) - 1
            var acc = 0L; var bits = 0; var pos = 0
            return IntArray(n) {
                while (bits < codeBits && pos < bytes.size) { acc = acc or ((bytes[pos++].toLong() and 0xFF) shl bits); bits += 8 }
                val v = (acc and mask).toInt(); acc = acc ushr codeBits; bits -= codeBits; v
            }
        }

        fun writeSpk(v: FloatArray): ByteArray {
            val b = java.nio.ByteBuffer.allocate(v.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (x in v) b.putFloat(x)
            return b.array()
        }
    }
}
