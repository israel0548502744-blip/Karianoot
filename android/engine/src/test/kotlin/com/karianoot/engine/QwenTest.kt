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

    /** Full clone (ICL) through the bridge equals the CLI with --ref-rvq/--ref-text (cli_icl_s0.wav, ref_ipa.txt). */
    @Test fun styledBridgeMatchesCommandLine() {
        assumeTrue(dir != null && System.getProperty("karianoot.qwenjni") != null && File(dir, "cli_icl_s0.wav").exists())
        QwenTts(File(dir, "qwen-talker-1.7b-base-Q4_K_M.gguf").path, File(dir, "qwen-tokenizer-12hz-Q4_K_M.gguf").path, null, 4).use { q ->
            val spk = QwenTts.readSpk(File(dir, "voices/q_male2.spk").readBytes())
            val codes = QwenTts.readRvq(File(dir, "voices/q_male2.rvq").readBytes(), q.numCodebooks)
            println("CHECK qwen rvq K=${q.numCodebooks} codes=${codes.size} max=${codes.max()}")
            val wav = q.speakStyled(File(dir, "s0.txt").readText().trim(), spk, codes, File(dir, "ref_ipa.txt").readText().trim(), 0f, 1)
            val (ref, _) = Wav.read(File(dir, "cli_icl_s0.wav"))
            val n = minOf(ref.size, wav.size)
            var d = 0.0; var a = 0.0; var b = 0.0
            for (i in 0 until n) { d += wav[i] * ref[i]; a += wav[i] * wav[i]; b += ref[i] * ref[i] }
            println("CHECK qwen styled ${wav.size} vs cli ${ref.size} corr=${"%.4f".format(d / sqrt(a * b + 1e-12))}")
            assertTrue(wav.size > 24000 && abs(wav.size - ref.size) < 2400)
        }
    }

    /**
     * The app's whole Qwen path on a football passage: TextPrep, the lexicon, the G2P, Qwen with TakeCheck retakes,
     * and the Joiner. Writes pipeline.wav (to listen to) and fails if a piece is still broken after retakes.
     */
    @Test fun appPipelineWithLexicon() {
        assumeTrue(dir != null && System.getProperty("karianoot.qwenjni") != null && File(dir, "renikud/model_int8.onnx").exists())
        val g2p = HebrewG2P(File(dir, "renikud/model_int8.onnx").path)
        QwenTts(File(dir, "qwen-talker-1.7b-base-Q4_K_M.gguf").path, File(dir, "qwen-tokenizer-12hz-Q4_K_M.gguf").path, g2p, 4).use { q ->
            val spk = QwenTts.readSpk(File("../app/src/main/assets/qwen/q_d_commentator.spk").readBytes())
            val text = TextPrep.apply(
                "הערב ארסנל נגד באיירן מינכן, ומבאפה עם ריאל מול ברצלונה. אני הולך על תיקו ביחס 3.40, " +
                    "והנדיקפ -1 לסיטי של גווארדיולה. מכבי נתניה מארחת את בית\"ר, ומ.ס. אשדוד נגד הפועל באר שבע. " +
                    "שימו 100 ש\"ח על 1.05, ובדקה 90+3 הכל יכול לקרות!")
            val t0 = System.nanoTime()
            val parts = q.synthesizeParts(text, spk)
            val sec = (System.nanoTime() - t0) / 1e9
            var broken = 0
            for (p in parts) {
                val ipa = q.ipa(p.text)
                val r = TakeCheck.measure(p.samples, q.sampleRate, ipa.length)
                if (r.broken) broken++
                println("CHECK piece rate=${"%.1f".format(r.rate)} pause=${r.longestPause} broken=${r.broken} | $ipa")
            }
            val j = Joiner.join(parts.map { it.samples }, parts.map { it.text }, q.sampleRate, 0f, QwenTts.NATURAL_PAUSE)
            Wav.write(File(dir, "pipeline.wav"), j.samples, q.sampleRate)
            println("CHECK pipeline ${"%.1f".format(j.samples.size / 24000.0)}s audio in ${"%.1f".format(sec)}s, pause ${j.pauseSeconds}")
            assertTrue("broken pieces after retakes: $broken", broken == 0)
        }
    }
}
