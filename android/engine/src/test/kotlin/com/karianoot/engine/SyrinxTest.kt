package com.karianoot.engine

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Blue 2.5 with its float graphs vs. the int8 "Syrinx" graphs (dtsj89/puki-syrinx, MIT): same text, voices, seeds and
 * steps; times both and writes the takes for the recognizer (syrinx.yml). KARIANOOT_SYRINX = dir with float/ and int8/
 * (full Blue 2.5 bundles), renikud/model_int8.onnx and passage.json. Skipped elsewhere.
 */
class SyrinxTest {
    @Test fun floatVsInt8() {
        val dir = System.getenv("KARIANOOT_SYRINX")?.takeIf { it.isNotEmpty() }?.let(::File)
        assumeTrue(dir != null && File(dir, "int8/vector_estimator.onnx").exists())
        val g2p = HebrewG2P(File(dir, "renikud/model_int8.onnx").path)
        val pieces = org.json.JSONObject(File(dir, "passage.json").readText()).getJSONArray("pieces")
        val texts = (0 until pieces.length()).map { pieces.getJSONObject(it).getString("text") }
        val out = File(dir, "takes").apply { mkdirs() }
        val times = JSONObject()
        for (kind in listOf("float", "int8")) BlueTts(File(dir, kind), g2p).use { tts ->
            for (vn in listOf("female", "libri_male_8088")) {
                val v = BlueVoice.load(File(dir, "float/voices/$vn.json"))
                for (steps in listOf(5, 10)) {
                    var work = 0.0; var audio = 0.0
                    texts.forEachIndexed { i, t ->
                        val opt = BlueTts.Options(steps = steps)
                        val t0 = System.nanoTime()
                        val wav = tts.join(tts.synthesizeParts(t, v, opt, BlueTts.gaussian(77L + i)), opt)
                        work += (System.nanoTime() - t0) / 1e9; audio += wav.size.toDouble() / tts.sampleRate
                        Wav.write(File(out, "${kind}_${vn}_s${steps}_$i.wav"), wav, tts.sampleRate)
                    }
                    times.put("${kind}_${vn}_s$steps", JSONObject().put("work_s", work).put("audio_s", audio).put("work_per_audio", work / audio))
                    println("CHECK syrinx $kind $vn steps=$steps work/audio=${"%.3f".format(work / audio)}")
                }
            }
        }
        File(dir, "times.json").writeText(times.toString(1))
    }
}
