package com.karianoot.engine

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Joins the spoken pieces of a text into one recording so that a join sounds like any other sentence break.
 * The user heard the old joins as "stop and go". Measured on Blue: a piece starts and ends right on the speech, so
 * the 25 ms edge fade clipped the first and last sounds; and a join was a fixed 0.15 s while Blue's own sentence
 * pauses inside a piece are ~0.1 s (Qwen's are ~0.4 s, longer than a join).
 *  - Each piece's silent edges are trimmed to a short margin and faded over 4 ms only (no clicks, no clipping).
 *  - Every sentence pause, inside a piece and between pieces, gets one length: the voice's own (the median of its
 *    sentence pauses in this recording) or the chosen one.
 *  - Pieces are evened out in loudness, at most ±3 dB.
 */
object Joiner {
    /** The joined recording and where each piece sits in it, [start, end) in samples (pauses excluded). */
    class Result(val samples: FloatArray, val spans: List<IntArray>, val pauseSeconds: Float)

    private const val HOP_MS = 10
    private const val QUIET_DB = -38.0       // below the piece's loudest 10 ms: a pause
    private const val MIN_RUN_MS = 50        // shorter quiet runs are gaps between sounds, not pauses
    private const val EDGE_DB = -55.0        // softer than a pause: a breathy "h" onset or a decay is kept (measured
                                             // ~-48 dB for the h of "היחס" in Qwen; silence is below -60)
    private const val MARGIN_MS = 10         // kept beyond the edge
    private const val MAX_LEAD_MS = 120      // at most this much soft sound before the first loud frame
    private const val MAX_TAIL_MS = 150      // and after the last
    private const val FADE_MS = 4
    private const val MIN_PAUSE = 0.06f

    /** A sentence end inside a piece: a stop mark followed by more text. */
    private val SENTENCE_END = Regex("[.!?…]+[\"'״׳)]*\\s+(?=\\S)")

    /** One piece measured: the speech span, its sentence pauses (sample ranges) and its loudness. */
    class Piece(val samples: FloatArray, val speechStart: Int, val speechEnd: Int, val pauses: List<IntArray>, val level: Double,
                /** Quiet samples kept before the first sound and after the last: they count toward the pause. */
                val leadQuiet: Int = 0, val tailQuiet: Int = 0)

    fun analyse(w: FloatArray, text: String, rate: Int): Piece {
        val h = max(1, rate * HOP_MS / 1000)
        val n = w.size / h
        if (n < 3) return Piece(w, 0, w.size, emptyList(), 0.0)
        val e = DoubleArray(n) { i -> var s = 0.0; for (k in i * h until (i + 1) * h) s += w[k].toDouble() * w[k]; sqrt(s / h) }
        val peak = e.max()
        if (peak < 1e-6) return Piece(w, 0, w.size, emptyList(), 0.0)
        val loud = BooleanArray(n) { 20 * log10(e[it] / peak + 1e-12) > QUIET_DB }
        val first = loud.indexOfFirst { it }
        val last = loud.indexOfLast { it }
        var lvl = 0.0; var cnt = 0
        for (i in first..last) if (loud[i]) { lvl += e[i] * e[i]; cnt++ }
        // Quiet runs inside the speech.
        val runs = ArrayList<IntArray>()
        var i = first
        while (i <= last) {
            if (!loud[i]) {
                val s = i
                while (i <= last && !loud[i]) i++
                if ((i - s) * HOP_MS >= MIN_RUN_MS) runs.add(intArrayOf(s, i))
            } else i++
        }
        // Match each sentence end in the text to the quiet run near where it should fall (text position ~ time).
        val t = text.trim()
        val chosen = ArrayList<IntArray>()
        if (t.isNotEmpty() && runs.isNotEmpty()) {
            val span = (last - first).toDouble()
            val window = max(60.0, 0.15 * span)
            val used = BooleanArray(runs.size)
            for (m in SENTENCE_END.findAll(t)) {
                val expected = first + span * m.range.last / t.length
                var best = -1; var bestCost = Double.MAX_VALUE
                for ((k, r) in runs.withIndex()) {
                    if (used[k]) continue
                    val center = (r[0] + r[1]) / 2.0
                    if (abs(center - expected) > window) continue
                    val cost = abs(center - expected) - 3.0 * (r[1] - r[0])
                    if (cost < bestCost) { bestCost = cost; best = k }
                }
                if (best >= 0) { used[best] = true; chosen.add(intArrayOf(runs[best][0] * h, runs[best][1] * h)) }
            }
            chosen.sortBy { it[0] }
        }
        // The edges: the first/last frame above EDGE_DB next to the speech, so soft onsets and decays are not cut.
        val soft = BooleanArray(n) { 20 * log10(e[it] / peak + 1e-12) > EDGE_DB }
        var a = first; val minA = max(0, first - MAX_LEAD_MS / HOP_MS)
        while (a > minA && soft[a - 1]) a--
        var b = last; val maxB = min(n - 1, last + MAX_TAIL_MS / HOP_MS)
        while (b < maxB && soft[b + 1]) b++
        val start = max(0, a * h - rate * MARGIN_MS / 1000)
        val end = min(w.size, (b + 1) * h + rate * MARGIN_MS / 1000)
        return Piece(w, start, end, chosen, if (cnt > 0) sqrt(lvl / cnt) else 0.0, first * h - start, max(0, end - (last + 1) * h))
    }

    /** The piece's speech with each sentence pause set to [pause] samples, its edges faded, times [gain]. */
    fun tidy(p: Piece, pause: Int, gain: Float, rate: Int): FloatArray {
        val out = FloatArrayBuilder(p.speechEnd - p.speechStart + p.pauses.size * pause)
        val x = rate * FADE_MS / 1000
        var pos = p.speechStart
        for (r in p.pauses) {
            if (r[0] < pos || r[1] > p.speechEnd) continue
            val len = r[1] - r[0]
            val keep = min(len, pause) / 2        // kept from each side of the pause (its decay and its lead-in)
            out.add(p.samples, pos, r[0] + keep)
            out.fadeOutTail(x)
            out.zeros(max(0, pause - 2 * keep))
            val resume = out.size
            out.add(p.samples, r[1] - keep, r[1])
            out.fadeInFrom(resume, x)
            pos = r[1]
        }
        out.add(p.samples, pos, p.speechEnd)
        val a = out.toArray()
        val f = min(x, a.size / 2)
        for (k in 0 until f) { val g = k.toFloat() / f; a[k] *= g; a[a.size - 1 - k] *= g }
        if (gain != 1f) for (k in a.indices) a[k] *= gain
        return a
    }

    /** The pause the voice itself makes at a sentence end: the median of its sentence pauses (seconds), or null. */
    fun naturalPause(pieces: List<Piece>, rate: Int): Float? {
        val lens = pieces.flatMap { p -> p.pauses.map { (it[1] - it[0]).toFloat() / rate } }.sorted()
        return if (lens.isEmpty()) null else lens[lens.size / 2]
    }

    /**
     * Joins [parts] (the pieces of [texts]). [pause] > 0: every sentence pause is that many seconds; otherwise the
     * voice's own pause, or [fallback] when the text has no sentence break to measure it on.
     */
    fun join(parts: List<FloatArray>, texts: List<String>, rate: Int, pause: Float = 0f, fallback: Float = 0.15f,
             peakLimit: Float? = 0.95f): Result {
        val pieces = parts.mapIndexed { i, w -> analyse(w, texts.getOrElse(i) { "" }, rate) }
        val sec = (if (pause > 0f) pause else naturalPause(pieces, rate) ?: fallback).coerceAtLeast(MIN_PAUSE)
        val gapSamples = (sec * rate).roundToInt()
        val levels = pieces.map { it.level }.filter { it > 0 }.sorted()
        val target = if (levels.isEmpty()) 0.0 else levels[levels.size / 2]
        val tidied = pieces.map { p ->
            val g = if (p.level > 0 && target > 0) (target / p.level).toFloat().coerceIn(0.71f, 1.41f) else 1f
            tidy(p, gapSamples, g, rate)
        }
        // The quiet margins kept at the edges count toward the pause between pieces.
        val between = IntArray(tidied.size) { i -> if (i == 0) 0 else max(0, gapSamples - pieces[i - 1].tailQuiet - pieces[i].leadQuiet) }
        val out = FloatArray(tidied.sumOf { it.size } + between.sum())
        val spans = ArrayList<IntArray>()
        var p = 0
        tidied.forEachIndexed { i, a ->
            p += between[i]
            a.copyInto(out, p); spans.add(intArrayOf(p, p + a.size)); p += a.size
        }
        return Result(peakLimit?.let { Wav.limitPeak(out, it) } ?: out, spans, sec)
    }

    /** Loudness of the speech in [w] (RMS of its loud 10 ms frames), to match a retake to the take it replaces. */
    fun level(w: FloatArray, rate: Int) = analyse(w, "", rate).level

    private class FloatArrayBuilder(capacity: Int) {
        private var a = FloatArray(max(16, capacity))
        var size = 0; private set
        private fun ensure(n: Int) { if (size + n > a.size) a = a.copyOf(max(a.size * 2, size + n)) }
        fun add(src: FloatArray, from: Int, to: Int) {
            if (to <= from) return
            ensure(to - from); src.copyInto(a, size, from, to); size += to - from
        }
        fun zeros(n: Int) { if (n > 0) { ensure(n); size += n } }
        fun fadeOutTail(n: Int) { val m = min(n, size); for (k in 0 until m) a[size - 1 - k] *= k.toFloat() / m }
        fun fadeInFrom(at: Int, n: Int) { val m = min(n, size - at); for (k in 0 until m) a[at + k] *= k.toFloat() / m }
        fun toArray() = a.copyOf(size)
    }
}
