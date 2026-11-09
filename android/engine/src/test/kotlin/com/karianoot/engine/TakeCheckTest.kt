package com.karianoot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class TakeCheckTest {
    private val rate = 24000

    /** Tone/silence segments (seconds, amplitude 0 = silence) -> samples. */
    private fun take(vararg seg: Pair<Double, Double>): FloatArray {
        val out = ArrayList<Float>()
        for ((sec, amp) in seg) repeat((sec * rate).toInt()) { out.add((amp * sin(2 * PI * 220.0 * out.size / rate)).toFloat()) }
        return out.toFloatArray()
    }

    @Test fun normalTakePasses() {
        // 90 IPA characters in ~4.9 s of speech = 18.4/s, pauses under 0.5 s.
        val w = take(0.1 to 0.0, 2.0 to 0.5, 0.4 to 0.0, 2.5 to 0.5, 0.2 to 0.0)
        assertFalse(TakeCheck.measure(w, rate, 90).broken)
    }

    @Test fun runOnStallAndDroppedWordsAreBroken() {
        // Ran on: 90 characters over 12 s.
        assertTrue(TakeCheck.measure(take(12.0 to 0.5), rate, 90).broken)
        // Dropped words: 90 characters in 2 s.
        assertTrue(TakeCheck.measure(take(2.0 to 0.5), rate, 90).broken)
        // Stall: a 1.5 s silence in the middle.
        val stall = TakeCheck.measure(take(2.0 to 0.5, 1.5 to 0.0, 2.0 to 0.5), rate, 100)
        assertTrue(stall.broken)
        // Nothing said at all.
        assertTrue(TakeCheck.measure(FloatArray(rate), rate, 50).broken)
    }

    /** Real takes (KARIANOOT_TAKES: lines "file.wav|ipa length"): prints the verdict for each, for tuning. */
    @Test fun realTakes() {
        val list = System.getenv("KARIANOOT_TAKES")?.takeIf { it.isNotEmpty() }?.let { java.io.File(it) }
        org.junit.Assume.assumeTrue(list != null && list.isFile)
        for (line in list!!.readLines().filter { it.contains('|') }) {
            val (f, n) = line.split('|')
            val (w, r) = Wav.read(java.io.File(f))
            val rep = TakeCheck.measure(w, r, n.toInt())
            println("CHECK take ${java.io.File(f).name} rate=${"%.1f".format(rep.rate)} pause=${rep.longestPause} broken=${rep.broken}")
        }
    }
}
