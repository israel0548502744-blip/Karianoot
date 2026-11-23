package com.karianoot.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Background music under a narration: resampled, looped, kept low ([level]) and faded in and out. */
object Mixer {
    /**
     * [voice] at [voiceRate] + [music] at [musicRate] -> one track at [voiceRate], as long as the voice plus [tail] seconds
     * of music. The music loops if it is shorter (with a short cross-fade at each loop), starts after a [fade]-second fade-in
     * and fades out over the tail. The result is limited to a 0.95 peak.
     */
    fun mix(voice: FloatArray, voiceRate: Int, music: FloatArray, musicRate: Int, level: Float = 0.15f,
            fade: Float = 1.5f, tail: Float = 1.5f, duck: Boolean = false, open: Float = 2.2f): FloatArray {
        val n = voice.size + (tail * voiceRate).toInt()
        val out = FloatArray(n)
        voice.copyInto(out)
        if (music.isEmpty()) return out
        val m = resample(music, musicRate, voiceRate)
        // Even loudness: the music's peak at 1, then [level].
        val peak = m.maxOf { abs(it) }.takeIf { it > 1e-6f } ?: return out
        val gain = level / peak
        val xf = min((0.05f * voiceRate).toInt(), m.size / 4)
        val fadeN = (fade * voiceRate).toInt().coerceAtLeast(1)
        val tailN = (tail * voiceRate).toInt().coerceAtLeast(1)
        // Ducking: the music sits at [level] under speech and rises to [open] x level in longer pauses and after the voice.
        val duckGain = if (duck) duckCurve(voice, voiceRate, n, open) else null
        for (i in 0 until n) {
            val j = i % m.size
            var s = m[j]
            // Cross-fade into the next loop so the seam does not click.
            if (m.size > 2 * xf && j >= m.size - xf && i + xf < n) { val t = (j - (m.size - xf)).toFloat() / xf; s = s * (1 - t) + m[j - (m.size - xf)] * t }
            val env = min(1f, i.toFloat() / fadeN) * min(1f, (n - i).toFloat() / tailN)
            out[i] += s * gain * env * (duckGain?.get(i) ?: 1f)
        }
        val p = out.maxOf { abs(it) }
        if (p > 0.95f) { val k = 0.95f / p; for (i in out.indices) out[i] *= k }
        return out
    }

    /**
     * Per sample, how much the music is raised: 1 while someone speaks, up to [open] in a pause of more than ~0.35 s
     * (it dips again 100 ms before the next words, so the first syllable is never covered) and after the voice ends.
     */
    fun duckCurve(voice: FloatArray, rate: Int, n: Int, open: Float): FloatArray {
        val hop = max(1, rate / 100)                 // 10 ms frames
        val frames = (voice.size + hop - 1) / hop
        val e = FloatArray(frames) { f -> var s = 0.0; val a = f * hop; val b = min(voice.size, a + hop); for (k in a until b) s += voice[k] * voice[k]; kotlin.math.sqrt(s / max(1, b - a)).toFloat() }
        val peak = e.maxOrNull()?.takeIf { it > 1e-6f } ?: return FloatArray(n) { open }
        val talk = BooleanArray(frames) { e[it] > peak * 0.01f }          // -40 dB below the loudest frame
        // Hold: gaps shorter than 350 ms count as speech (breaths, sentence pauses inside a thought).
        val hold = 35
        var lastTalk = -10_000
        val active = BooleanArray(frames)
        for (f in 0 until frames) { if (talk[f]) lastTalk = f; active[f] = f - lastTalk < hold }
        // Look ahead: start ducking 100 ms before speech starts.
        var nextTalk = Int.MAX_VALUE
        for (f in frames - 1 downTo 0) { if (talk[f]) nextTalk = f; if (nextTalk - f in 0..10) active[f] = true }
        val down = kotlin.math.exp(-1.0 / (0.02 * rate)).toFloat()      // 20 ms to duck
        val up = kotlin.math.exp(-1.0 / (0.5 * rate)).toFloat()         // 500 ms to rise
        val out = FloatArray(n)
        var g = 1f
        for (i in 0 until n) {
            val f = i / hop
            val target = if (f < frames && active[f]) 1f else open
            g = if (target < g) down * g + (1 - down) * target else up * g + (1 - up) * target
            out[i] = g
        }
        return out
    }

    /** Linear resampling (enough for background music). */
    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || x.isEmpty()) return x
        val n = max(1, (x.size.toLong() * to / from).toInt())
        val r = from.toDouble() / to
        return FloatArray(n) { i -> val p = i * r; val k = p.toInt(); val f = (p - k).toFloat()
            x[min(k, x.size - 1)] * (1 - f) + x[min(k + 1, x.size - 1)] * f }
    }
}
