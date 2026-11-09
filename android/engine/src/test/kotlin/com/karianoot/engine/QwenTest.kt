package com.karianoot.engine

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * QwenTTS-he through the JNI bridge (host build of android/qwen/qwen_jni.cpp), compared with the qwentts.cpp
 * command line on the same input. Runs in .github/workflows/qwen-voices.yml; skipped elsewhere.
 * KARIANOOT_QWEN = dir with the two GGUFs, the voice prints (voices dir, .spk), s0.txt (IPA) and cli_s0.wav (qwen-tts --greedy output).
 */
class QwenTest {
    private val dir = System.getenv("KARIANOOT_QWEN")?.takeIf { it.isNotEmpty() }?.let(::File)

    @Test fun bridgeMatchesCommandLine() {
        assumeTrue(dir != null && System.getProperty("karianoot.qwenjni") != null)
        val t0 = System.nanoTime()
        QwenTts(File(dir, "qwen-talker-1.7b-base-Q4_K_M.gguf").path, File(dir, "qwen-tokenizer-12hz-Q4_K_M.gguf").path, null, 4).use { q ->
            println("CHECK qwen load ${"%.1f".format((System.nanoTime() - t0) / 1e9)}s")
            val spk = QwenTts.readSpk(File(dir, "voices/q_male2.spk").readBytes())
            val ipa = File(dir, "s0.txt").readText().trim()
            val t1 = System.nanoTime()
            val wav = q.speakIpa(ipa, spk, 0f, 1)
            val sec = (System.nanoTime() - t1) / 1e9
            Wav.write(File(dir, "jni_s0.wav"), Wav.limitPeak(wav), q.sampleRate)
            val (ref, rate) = Wav.read(File(dir, "cli_s0.wav"))
            val n = minOf(ref.size, wav.size)
            var d = 0.0; var a = 0.0; var b = 0.0
            for (i in 0 until n) { d += wav[i] * ref[i]; a += wav[i] * wav[i]; b += ref[i] * ref[i] }
            val corr = d / sqrt(a * b + 1e-12)
            println("CHECK qwen jni ${wav.size} samples (${"%.1f".format(wav.size / 24000.0)}s audio in ${"%.1f".format(sec)}s) cli ${ref.size} @${rate} corr=${"%.4f".format(corr)}")
            assertTrue("no audio", wav.size > 24000)
            assertTrue("length differs from the command line", abs(wav.size - ref.size) < 2400)
        }
    }
}
