package com.karianoot.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Compares the Kotlin engine with the original Python packages (golden data from
 * tools/reference.py). Skipped when KARIANOOT_MODELS / KARIANOOT_GOLDEN are not set.
 */
class GoldenTest {
    private val models = System.getenv("KARIANOOT_MODELS")?.takeIf { it.isNotEmpty() }?.let(::File)
    private val golden = System.getenv("KARIANOOT_GOLDEN")?.takeIf { it.isNotEmpty() }?.let(::File)
    private val out = File("build/test-out").apply { mkdirs() }

    private fun need() = assumeTrue("no models/golden data", models != null && golden != null)

    @Test fun g2pMatchesPython() {
        need()
        val g2p = HebrewG2P(File(models, "renikud/model_int8.onnx").path)
        val cases = JSONArray(File(golden, "g2p.json").readText())
        var bad = 0
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val t0 = System.nanoTime()
            val got = g2p.phonemize(c.getString("text"))
            val ms = (System.nanoTime() - t0) / 1e6
            val ok = got == c.getString("ipa")
            if (!ok) bad++
            println("CHECK g2p ${if (ok) "PASS" else "FAIL"} (${"%.0f".format(ms)} ms) ${c.getString("text")}")
            if (!ok) println("CHECK   python: ${c.getString("ipa")}\nCHECK   kotlin: $got")
        }
        assertEquals("G2P mismatches", 0, bad)
    }

    @Test fun blueMatchesPython() {
        need()
        val tags = golden!!.listFiles()!!.map { it.name }.filter { it.startsWith("blue_") && it.endsWith(".json") }.sorted()
        assertTrue("no Blue golden files", tags.isNotEmpty())
        val engines = HashMap<String, BlueTts>()
        var worst = 0.0
        for (name in tags) {
            val tag = name.removeSuffix(".json")
            val parts = tag.split("_")               // blue, v3, Roi, 0
            val bundle = parts[0] + "_" + parts[1]
            val voiceName = parts.subList(2, parts.size - 1).joinToString("_")
            val tts = engines.getOrPut(bundle) { BlueTts(File(models, bundle), null) }
            val voice = BlueVoice.load(File(models, "$bundle/voices/$voiceName.json"))
            val ref = JSONObject(File(golden, name).readText())
            val ipa = ref.getString("ipa")

            val ids = tts.encode(tts.preprocess(ipa)).toList()
            val refIds = ref.getJSONArray("ids").let { a -> (0 until a.length()).map { a.getLong(it) } }
            assertEquals("token ids for $tag", refIds, ids)

            val noise = Npy.read(File(golden, "${tag}_noise.npy"))
            val t0 = System.nanoTime()
            val (wav, dur) = tts.infer(ipa, voice, BlueTts.Options(), BlueTts.Noise { noise.data.copyOf() })
            val sec = (System.nanoTime() - t0) / 1e9
            val refWav = Npy.read(File(golden, "${tag}_wav.npy")).data
            Wav.write(File(out, "$tag.wav"), Wav.limitPeak(wav), tts.sampleRate)

            val n = minOf(wav.size, refWav.size)
            var dot = 0.0; var a2 = 0.0; var b2 = 0.0; var maxDiff = 0.0
            for (i in 0 until n) {
                dot += wav[i] * refWav[i]; a2 += wav[i] * wav[i]; b2 += refWav[i] * refWav[i]
                maxDiff = maxOf(maxDiff, abs(wav[i] - refWav[i]).toDouble())
            }
            val corr = dot / sqrt(a2 * b2 + 1e-12)
            worst = maxOf(worst, maxDiff)
            val audioSec = wav.size.toDouble() / tts.sampleRate
            println("CHECK blue $tag dur py=${"%.3f".format(ref.getDouble("dur"))} kt=${"%.3f".format(dur)} " +
                "len py=${refWav.size} kt=${wav.size} corr=${"%.5f".format(corr)} maxdiff=${"%.4f".format(maxDiff)} " +
                "time=${"%.1f".format(sec)}s audio=${"%.1f".format(audioSec)}s")
            assertEquals("length for $tag", refWav.size, wav.size)
            assertTrue("audio for $tag differs (corr=$corr)", corr > 0.999)
        }
        engines.values.forEach { it.close() }
        println("CHECK blue worst maxdiff=$worst")
    }

    @Test fun fullPipelineWritesWav() {
        need()
        val g2p = HebrewG2P(File(models, "renikud/model_int8.onnx").path)
        BlueTts(File(models, "blue_v3"), g2p).use { tts ->
            val voice = BlueVoice.load(File(models, "blue_v3/voices/Roi.json"))
            val text = "ערב טוב לכולם. היום יש לנו שלושה משחקים מעולים, ואני הולך להסביר לכם בדיוק על מה לשים את הכסף."
            val t0 = System.nanoTime()
            val wav = tts.synthesize(text, voice, BlueTts.Options(silence = 0.15f))
            val sec = (System.nanoTime() - t0) / 1e9
            Wav.write(File(out, "full_v3_roi.wav"), wav, tts.sampleRate)
            println("CHECK full ${"%.1f".format(wav.size.toDouble() / tts.sampleRate)}s audio in ${"%.1f".format(sec)}s")
            assertTrue(wav.size > tts.sampleRate)
        }
    }

    @Test fun voiceFromRecordingMatchesPython() {
        need()
        val input = Npy.read(File(golden, "clone_input.npy")).data
        val t0 = System.nanoTime()
        val mel = VoiceCloner.features(input)
        val refMel = Npy.read(File(golden, "clone_mel.npy"))
        assertEquals("mel shape", refMel.shape.toList(), mel.shape.toList())
        var md = 0.0
        for (i in mel.data.indices) md = maxOf(md, abs(mel.data[i] - refMel.data[i]).toDouble())
        println("CHECK clone mel maxdiff=${"%.5f".format(md)} shape=${mel.shape.toList()} (${"%.0f".format((System.nanoTime() - t0) / 1e6)} ms)")
        assertTrue("mel differs: $md", md < 5e-2) // log domain; tiny float rounding on near-silent bins

        VoiceCloner(File(models, "clone/codec_encoder.onnx"), File(models, "blue_25/reference_encoder.onnx")).use { cl ->
            val v = cl.clone(input)
            val z = Npy.read(File(golden, "clone_z.npy")); val ttl = Npy.read(File(golden, "clone_ttl.npy"))
            assertEquals("z shape", z.shape.toList(), v.zRef!!.shape.toList())
            val cz = corr(v.zRef.data, z.data); val ct = corr(v.ttl.data, ttl.data)
            println("CHECK clone z corr=${"%.6f".format(cz)} ttl corr=${"%.6f".format(ct)}")
            assertTrue(cz > 0.999 && ct > 0.999)
            val ref = JSONObject(File(golden, "clone.json").readText())
            BlueTts(File(models, "blue_25"), null, File(models, "blue_25/duration_predictor.onnx")).use { tts ->
                val (wav, dur) = tts.infer(ref.getString("ipa"), v, BlueTts.Options(steps = 10), BlueTts.gaussian(1))
                println("CHECK clone dur py=${"%.3f".format(ref.getDouble("dur"))} kt=${"%.3f".format(dur)}")
                assertEquals(ref.getDouble("dur"), dur.toDouble(), 0.01)
                Wav.write(File(out, "clone_voice.wav"), Wav.limitPeak(wav), tts.sampleRate)
            }
        }
    }

    private fun corr(a: FloatArray, b: FloatArray): Double {
        var d = 0.0; var x = 0.0; var y = 0.0
        for (i in a.indices) { d += a[i] * b[i]; x += a[i] * a[i]; y += b[i] * b[i] }
        return d / sqrt(x * y + 1e-12)
    }
}
