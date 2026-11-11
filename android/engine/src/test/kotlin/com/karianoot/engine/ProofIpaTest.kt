package com.karianoot.engine

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The automatic proofreader's first half (tools/proof, .github/workflows/proofread.yml): every sentence of
 * tools/proof/sentences.txt through the app's own text path (TextPrep, pieces, lexicon + G2P, Magpie's symbol filter),
 * written as pieces.jsonl for magpie-cli + the recognizer. KARIANOOT_PROOF = dir with sentences.txt and
 * renikud/model_int8.onnx. Skipped elsewhere.
 */
class ProofIpaTest {
    @Test fun writePieces() {
        val dir = System.getenv("KARIANOOT_PROOF")?.takeIf { it.isNotEmpty() }?.let(::File)
        assumeTrue(dir != null && File(dir, "renikud/model_int8.onnx").exists())
        val g2p = HebrewG2P(File(dir, "renikud/model_int8.onnx").path)
        var n = 0
        File(dir, "pieces.jsonl").printWriter().use { out ->
            File(dir, "sentences.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { s, line ->
                for ((p, piece) in MagpieTts.pieces(TextPrep.apply(line)).withIndex()) {
                    val ipa = MagpieTts.clean(BlueTts.toIpa(g2p, piece))
                    out.println(JSONObject().put("sentence", s).put("piece", p).put("source", line).put("text", piece)
                        .put("spoken", HebrewNumbers.normalize(piece))
                        .put("ipa", ipa).toString())
                    n++
                }
            }
        }
        println("CHECK proof pieces $n")
    }
}
