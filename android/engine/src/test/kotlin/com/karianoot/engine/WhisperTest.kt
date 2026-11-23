package com.karianoot.engine

import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The phone proofreader's recognizer through the JNI bridge (whisper-jni.yml): Magpie takes of the test passage, heard
 * through WhisperAsr, scored against their text, and Proofread on them. KARIANOOT_WHISPER = model, KARIANOOT_WHISPER_WAVS
 * = dir with rows.json ({file, text}); -Dkarianoot.whisperjni = the host library.
 */
class WhisperTest {
    @Test fun hearsTheTakes() {
        val model = System.getenv("KARIANOOT_WHISPER")?.takeIf { it.isNotEmpty() }
        val dir = System.getenv("KARIANOOT_WHISPER_WAVS")?.takeIf { it.isNotEmpty() }?.let(::File)
        assumeTrue(model != null && dir != null && System.getProperty("karianoot.whisperjni") != null)
        val rows = JSONArray(File(dir, "rows.json").readText())
        var cer = 0.0; var audio = 0.0
        val t0 = System.nanoTime()
        WhisperAsr(model!!, threads = 4).use { asr ->
            for (i in 0 until rows.length()) {
                val o = rows.getJSONObject(i)
                val (a, rate) = Wav.read(File(o.getString("file")))
                audio += a.size.toDouble() / rate
                val heard = asr.hear(a, rate)
                val ref = Proofread.words(o.getString("text")).joinToString(""); val hyp = Proofread.words(heard).joinToString("")
                val c = 1.0 - Proofread.similarity(ref, hyp)
                cer += c
                println("CHECK whisper [${"%.3f".format(c)}] $heard | missed: ${Proofread.missed(o.getString("text"), heard).map { it.ref }}")
            }
        }
        val work = (System.nanoTime() - t0) / 1e9
        println("CHECK whisper mean 1-sim ${"%.3f".format(cer / rows.length())}, work/audio ${"%.2f".format(work / audio)} (4 threads)")
        assertTrue(cer / rows.length() < 0.1)
    }
}
