package com.karianoot.engine

/**
 * JNI bridge to whisper.cpp (android/whisper/whisper_jni.cpp): ivrit-ai's Hebrew recognizer on the phone, for the
 * in-app proofreader. libwhisperjni.so is built in CI for arm64-v8a (and for the host in whisper-jni.yml).
 */
object WhisperNative {
    @Volatile private var loaded = false

    @Synchronized fun loadLibrary(path: String? = null) {
        if (loaded) return
        if (path != null) System.load(path) else System.loadLibrary("whisperjni")
        loaded = true
    }

    @JvmStatic external fun load(model: String): Long
    @JvmStatic external fun free(handle: Long)
    @JvmStatic external fun cancel()
    /** 16 kHz mono -> text. beam <= 1: greedy. */
    @JvmStatic external fun transcribe(handle: Long, pcm16k: FloatArray, language: String, threads: Int, beam: Int): String
}

/** The recognizer, loaded once: audio at any rate -> the Hebrew it hears. */
class WhisperAsr(model: String, private val threads: Int = 4, private val beam: Int = 1) : AutoCloseable {
    private val h: Long

    init {
        WhisperNative.loadLibrary(System.getProperty("karianoot.whisperjni"))
        h = WhisperNative.load(model)
    }

    fun hear(samples: FloatArray, rate: Int): String {
        // A little silence around the words helps the recognizer with the first and last word.
        val x = if (rate == 16000) samples else VoiceCloner.resample(samples, rate, 16000)
        val padded = FloatArray(x.size + 8000).also { x.copyInto(it, 4000) }
        return WhisperNative.transcribe(h, padded, "he", threads, beam).trim()
    }

    fun cancel() = WhisperNative.cancel()
    override fun close() = WhisperNative.free(h)
}
