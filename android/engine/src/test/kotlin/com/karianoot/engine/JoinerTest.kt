package com.karianoot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class JoinerTest {
    private val rate = 24000

    /** Tone/silence segments (seconds, amplitude 0 = silence) -> samples. */
    private fun piece(vararg seg: Pair<Double, Double>): FloatArray {
        val out = ArrayList<Float>()
        for ((sec, amp) in seg) repeat((sec * rate).toInt()) { out.add((amp * sin(2 * PI * 220.0 * out.size / rate)).toFloat()) }
        return out.toFloatArray()
    }

    /** Quiet runs (10 ms frames below 0.01 RMS) longer than 30 ms, in seconds, between the first and last sound. */
    private fun pauses(a: FloatArray): List<Double> {
        val h = rate / 100
        val q = BooleanArray(a.size / h) { i -> var s = 0.0; for (k in i * h until (i + 1) * h) s += a[k] * a[k]; sqrt(s / h) < 0.01 }
        val first = q.indexOfFirst { !it }; val last = q.indexOfLast { !it }
        val runs = ArrayList<Double>(); var c = 0
        for (i in first..last) { if (q[i]) c++ else { if (c >= 3) runs.add(c / 100.0); c = 0 } }
        return runs
    }

    private fun rms(a: FloatArray, from: Int, to: Int): Double { var s = 0.0; for (i in from until to) s += a[i] * a[i]; return sqrt(s / (to - from)) }

    // Two sentences with a long pause between them, a comma pause, and trailing silence (like a Qwen piece).
    private val a = piece(0.5 to 0.5, 0.30 to 0.0, 0.4 to 0.5, 0.08 to 0.0, 0.3 to 0.5, 0.25 to 0.0)
    private val ta = "אחת שתיים שלוש ארבע. חמש שש, שבע."
    // One sentence that starts and ends right on the sound (like a Blue piece).
    private val b = piece(0.6 to 0.5, 0.06 to 0.0, 0.4 to 0.5)
    private val tb = "שמונה תשע, עשר."

    @Test fun everySentencePauseGetsTheChosenLength() {
        val r = Joiner.join(listOf(a, b), listOf(ta, tb), rate, pause = 0.2f)
        val p = pauses(r.samples)
        // Inside piece a: the sentence pause 0.30 -> 0.20, the comma pause 0.08 stays; the join a|b is 0.20 too;
        // the short gap inside b (0.06) stays.
        assertEquals(4, p.size)
        assertEquals(0.20, p[0], 0.02)
        assertEquals(0.08, p[1], 0.02)
        assertEquals(0.20, p[2], 0.02)
        assertEquals(0.06, p[3], 0.02)
        assertEquals(0.2f, r.pauseSeconds, 1e-6f)
    }

    @Test fun naturalPauseIsTheVoicesOwn() {
        val r = Joiner.join(listOf(a, b), listOf(ta, tb), rate, pause = 0f, fallback = 0.5f)
        // The only sentence pause measured is 0.30 s, so the join becomes 0.30 s as well.
        assertEquals(0.30f, r.pauseSeconds, 0.011f)
        val p = pauses(r.samples)
        assertEquals(p[0], p[2], 0.02)
        // No sentence break anywhere: the fallback.
        assertEquals(0.5f, Joiner.join(listOf(b, b), listOf(tb, tb), rate, fallback = 0.5f).pauseSeconds, 1e-6f)
    }

    @Test fun floorLengthensShortNaturalPauses() {
        // The voice's own pause is 0.30 s; a floor of 0.4 s wins, a floor of 0.2 s changes nothing.
        assertEquals(0.4f, Joiner.join(listOf(a, b), listOf(ta, tb), rate, fallback = 0.5f, floor = 0.4f).pauseSeconds, 1e-6f)
        assertEquals(0.30f, Joiner.join(listOf(a, b), listOf(ta, tb), rate, fallback = 0.5f, floor = 0.2f).pauseSeconds, 0.011f)
        // A chosen pause is kept as it is.
        assertEquals(0.15f, Joiner.join(listOf(a, b), listOf(ta, tb), rate, pause = 0.15f, floor = 0.4f).pauseSeconds, 1e-6f)
    }

    @Test fun onsetIsNotClipped() {
        val r = Joiner.join(listOf(b, b), listOf(tb, tb), rate, pause = 0.2f, peakLimit = null)
        val s = r.spans[1][0]
        // b starts right on the sound: after a 4 ms fade the second piece is at full level (the old 25 ms fade halved
        // the first 12 ms).
        assertEquals(rms(b, 0, rate / 50), rms(r.samples, s + rate / 200, s + rate / 200 + rate / 50), 0.02)
        assertEquals(2, r.spans.size)
        assertTrue(r.spans[0][1] <= r.spans[1][0])
    }

    @Test fun quietPieceIsEvenedOut() {
        val quiet = FloatArray(b.size) { b[it] * 0.7f }
        val r = Joiner.join(listOf(b, quiet, b), listOf(tb, tb, tb), rate, pause = 0.2f, peakLimit = null)
        val l0 = rms(r.samples, r.spans[0][0] + 2400, r.spans[0][0] + 12000)
        val l1 = rms(r.samples, r.spans[1][0] + 2400, r.spans[1][0] + 12000)
        assertEquals(l0, l1, 0.01)
    }

    /**
     * Real pieces (KARIANOOT_JOIN: a folder with <name>.txt listing "piece.wav|text" per line) joined the old way
     * (25 ms fades, a fixed 0.15 s) and the new way, for listening and measuring.
     */
    @Test fun realPieces() {
        val dir = System.getenv("KARIANOOT_JOIN")?.takeIf { it.isNotEmpty() }?.let { java.io.File(it) }
        org.junit.Assume.assumeTrue(dir != null && dir.isDirectory)
        for (list in dir!!.listFiles { f -> f.name.endsWith(".txt") }!!.sorted()) {
            val rows = list.readLines().filter { it.contains('|') }.map { it.split('|', limit = 2) }
            val wavs = rows.map { Wav.read(java.io.File(dir, it[0])) }
            val r = wavs[0].second
            val old = wavs.map { BlueTts.fadeEdges(it.first.copyOf(), r) }
            val gap = (0.15f * r).toInt()
            val o = FloatArray(old.sumOf { it.size } + gap * (old.size - 1)); var p = 0
            old.forEachIndexed { i, a -> if (i > 0) p += gap; a.copyInto(o, p); p += a.size }
            val name = list.name.removeSuffix(".txt")
            Wav.write(java.io.File(dir, "${name}_old.wav"), Wav.limitPeak(o), r)
            val qwen = r == 24000
            val n = Joiner.join(wavs.map { it.first }, rows.map { it[1] }, r, 0f, if (qwen) 0.4f else 0.1f,
                floor = if (qwen) QwenTts.MIN_PAUSE else BlueTts.MIN_PAUSE)
            Wav.write(java.io.File(dir, "${name}_new.wav"), n.samples, r)
            println("CHECK join $name natural pause ${n.pauseSeconds}")
            // Fixed pauses, to choose by ear.
            for (sec in listOf(0.11f, 0.3f)) {
                val f = Joiner.join(wavs.map { it.first }, rows.map { it[1] }, r, sec)
                Wav.write(java.io.File(dir, "${name}_p${(sec * 100).toInt()}.wav"), f.samples, r)
            }
        }
    }
}
