package com.karianoot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

/**
 * Magpie Hebrew through the JNI bridge (host build of android/magpie/magpie_jni.cpp), compared with magpie-cli on the
 * same input. Runs in .github/workflows/magpie-jni.yml; skipped elsewhere.
 * KARIANOOT_MAGPIE = dir with magpie-he-q8_0.gguf, s0.txt (IPA), cli_s0.wav (magpie-cli say --lang he --speaker 10
 * --seed 7 --threads 4) and renikud/model_int8.onnx.
 */
class MagpieTest {
    private val dir = System.getenv("KARIANOOT_MAGPIE")?.takeIf { it.isNotEmpty() }?.let(::File)
    private fun ready() = dir != null && System.getProperty("karianoot.magpiejni") != null
    private fun gguf() = File(dir, "magpie-he-q8_0.gguf").path

    @Test fun bridgeMatchesCommandLine() {
        assumeTrue(ready())
        val t0 = System.nanoTime()
        MagpieTts(gguf(), null, 4).use { m ->
            println("CHECK magpie load ${"%.1f".format((System.nanoTime() - t0) / 1e9)}s rate ${m.sampleRate}")
            val ipa = File(dir, "s0.txt").readText().trim()
            // The CLI's settings: the model's defaults, seed 7, 4 threads.
            val t1 = System.nanoTime()
            val wav = MagpieNative.synthesize(handleOf(m), ipa, "he", 10, -1f, -1, -1f, 7, 4, -1)
            val sec = (System.nanoTime() - t1) / 1e9
            Wav.write(File(dir, "jni_s0.wav"), wav, m.sampleRate)
            val (ref, rate) = Wav.read(File(dir, "cli_s0.wav"))
            val n = minOf(ref.size, wav.size)
            var d = 0.0; var a = 0.0; var b = 0.0
            for (i in 0 until n) { d += wav[i] * ref[i]; a += wav[i] * wav[i]; b += ref[i] * ref[i] }
            val corr = d / sqrt(a * b + 1e-12)
            println("CHECK magpie jni ${wav.size} samples (${"%.1f".format(wav.size.toDouble() / m.sampleRate)}s audio in " +
                "${"%.1f".format(sec)}s) cli ${ref.size} @$rate corr=${"%.4f".format(corr)}")
            assertEquals(rate, m.sampleRate)
            assertEquals("length differs from the command line", ref.size, wav.size)
            assertTrue("audio differs from the command line: corr $corr", corr > 0.999)
        }
    }

    /** The app's whole path: TextPrep, pieces, lexicon + G2P, Magpie (app settings, speaker 10) with retakes, Joiner. */
    @Test fun appPipelineIsCleanAndCancelWorks() {
        assumeTrue(ready() && File(dir, "renikud/model_int8.onnx").exists())
        val g2p = HebrewG2P(File(dir, "renikud/model_int8.onnx").path)
        MagpieTts(gguf(), g2p, 4).use { m ->
            val text = TextPrep.apply(
                "ערב טוב לכל האוהדים! הערב ארסנל נגד באיירן מינכן, ומבאפה עם ריאל מול ברצלונה. " +
                    "אני הולך על תיקו ביחס 3.40, והנדיקפ -1 לסיטי של גווארדיולה. מי שעוד לא הצטרף לקבוצה, זה הזמן!")
            val t0 = System.nanoTime()
            val parts = m.synthesizeParts(text, 10, seed = 11)
            val sec = (System.nanoTime() - t0) / 1e9
            var broken = 0
            for (p in parts) {
                val ipa = m.ipa(p.text)
                val r = TakeCheck.measure(p.samples, m.sampleRate, ipa.length, TakeCheck.MAGPIE)
                val rms = sqrt(p.samples.fold(0.0) { s, x -> s + x * x } / p.samples.size)
                if (r.broken || rms < 0.01) broken++
                println("CHECK piece rate=${"%.1f".format(r.rate)} pause=${r.longestPause} rms=${"%.3f".format(rms)} broken=${r.broken} | $ipa")
            }
            val j = Joiner.join(parts.map { it.samples }, parts.map { it.text }, m.sampleRate, 0f, MagpieTts.NATURAL_PAUSE,
                floor = MagpieTts.MIN_PAUSE)
            Wav.write(File(dir, "pipeline.wav"), j.samples, m.sampleRate)
            val audio = j.samples.size.toDouble() / m.sampleRate
            println("CHECK pipeline ${"%.1f".format(audio)}s audio in ${"%.1f".format(sec)}s (work/audio ${"%.2f".format(sec / audio)})")
            assertTrue("broken or silent pieces after retakes: $broken", broken == 0)

            // Cancel stops a synthesis mid-way, and the next one works.
            val ipa = m.ipa(parts.first().text)
            Thread { Thread.sleep(800); m.cancel() }.start()
            var cancelled = false
            try { m.speakIpa(ipa, 10) } catch (e: java.util.concurrent.CancellationException) { cancelled = true }
            println("CHECK cancel stopped=$cancelled")
            assertTrue("cancel did not stop the synthesis", cancelled)
            assertTrue(m.speakIpa(ipa, 10, seed = 3).size > m.sampleRate)
        }
    }

    private fun handleOf(m: MagpieTts): Long = MagpieTts::class.java.getDeclaredField("h").apply { isAccessible = true }.getLong(m)
}
