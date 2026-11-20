package com.karianoot.engine

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The proofreader for Blue (tools/proof, proofread.yml job "blue"): every piece of pieces.jsonl spoken with Blue 2.5
 * through the app's path (lexicon + G2P + IPA fixes, 10 steps = the app's "good"), written as s<sentence>_<piece>_spk<voice>.wav
 * for the recognizer. KARIANOOT_PROOF = dir with pieces.jsonl and renikud/; KARIANOOT_MODELS = dir with blue_25/.
 * PROOF_SHARD / PROOF_SHARDS split the work. Skipped elsewhere.
 */
class BlueProofTest {
    @Test fun speakPieces() {
        val dir = System.getenv("KARIANOOT_PROOF")?.takeIf { it.isNotEmpty() }?.let(::File)
        val models = System.getenv("KARIANOOT_MODELS")?.takeIf { it.isNotEmpty() }?.let(::File)
        assumeTrue(dir != null && models != null && File(dir, "pieces.jsonl").exists() && File(models, "blue_25").exists())
        val shard = System.getenv("PROOF_SHARD")?.toIntOrNull() ?: 0
        val shards = System.getenv("PROOF_SHARDS")?.toIntOrNull() ?: 1
        val out = File(dir, "blue").apply { mkdirs() }
        val g2p = HebrewG2P(File(dir, "renikud/model_int8.onnx").path)
        BlueTts(File(models, "blue_25"), g2p).use { tts ->
            val voices = listOf("female", "libri_male_8088").associateWith { BlueVoice.load(File(models, "blue_25/voices/$it.json")) }
            File(dir, "pieces.jsonl").readLines().filter { it.isNotBlank() }.withIndex().filter { it.index % shards == shard }.forEach { (_, line) ->
                val p = JSONObject(line)
                for ((name, v) in voices) {
                    val opt = BlueTts.Options(steps = 10)
                    val wav = tts.join(tts.synthesizeParts(p.getString("text"), v, opt, BlueTts.gaussian(1000L + p.getInt("sentence"))), opt)
                    Wav.write(File(out, "s${p.getInt("sentence")}_${p.getInt("piece")}_spk$name.wav"), wav, tts.sampleRate)
                }
            }
        }
        println("CHECK blue proof shard $shard done: ${out.list()?.size} wavs")
    }
}
