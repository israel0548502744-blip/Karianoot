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
            fade: Float = 1.5f, tail: Float = 1.5f): FloatArray {
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
        for (i in 0 until n) {
            val j = i % m.size
            var s = m[j]
            // Cross-fade into the next loop so the seam does not click.
            if (m.size > 2 * xf && j >= m.size - xf && i + xf < n) { val t = (j - (m.size - xf)).toFloat() / xf; s = s * (1 - t) + m[j - (m.size - xf)] * t }
            val env = min(1f, i.toFloat() / fadeN) * min(1f, (n - i).toFloat() / tailN)
            out[i] += s * gain * env
        }
        val p = out.maxOf { abs(it) }
        if (p > 0.95f) { val k = 0.95f / p; for (i in out.indices) out[i] *= k }
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
