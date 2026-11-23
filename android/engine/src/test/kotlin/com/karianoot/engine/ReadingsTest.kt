package com.karianoot.engine

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * "איך זה ייקרא" on the proofreader's 485 sentences with the real G2P (proofread.yml, KARIANOOT_PROOF): how many come
 * out word by word, and what a few look like in Hebrew with niqqud. Skipped without the model.
 */
class ReadingsTest {
    @Test fun sentencesPairWordByWord() {
        val dir = System.getenv("KARIANOOT_PROOF")?.takeIf { it.isNotEmpty() }?.let(::File)
        assumeTrue(dir != null && File(dir, "renikud/model_int8.onnx").exists())
        val g2p = HebrewG2P(File(dir, "renikud/model_int8.onnx").path)
        var ok = 0; var total = 0
        val bad = ArrayList<String>()
        File(dir, "sentences.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { i, line ->
            for (s in TextPrep.apply(line).split(Readings.SENTENCES).map { it.trim() }.filter { it.isNotEmpty() }) {
                total++
                val ipa = BlueTts.toIpa(g2p, s)
                val p = Readings.pair(s, ipa)
                if (p != null) {
                    ok++
                    if (i % 60 == 0) println("CHECK READ " + p.joinToString("  ") { (w, x) -> "$w=" + IpaHebrew.render(x).joinToString("·") { if (it.stressed) "[${it.text}]" else it.text } })
                } else if (bad.size < 25) bad.add("$s | $ipa")
            }
        }
        bad.forEach { println("CHECK UNPAIRED $it") }
        println("CHECK readings paired $ok / $total")
        assertTrue("paired $ok / $total", ok >= total * 0.9)
    }
}
