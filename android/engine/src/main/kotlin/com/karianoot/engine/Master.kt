package com.karianoot.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "סאונד אולפן": the finishing a sound engineer gives a voiceover, in one call.
 *  - A high-pass at 80 Hz (rumble, mic handling), a little less 300 Hz (boxiness), a little more 3.5 kHz (presence).
 *  - A gentle compressor (3:1 above -20 dBFS) so quiet words and loud words sit closer together.
 *  - Loudness set to -14 LUFS (what TikTok / Instagram / YouTube play at: a quieter upload sounds weak next to others,
 *    a louder one is turned down and squashed).
 *  - A look-ahead limiter so nothing goes over -1 dBFS (no clipping on phones).
 * Loudness is measured as in ITU-R BS.1770-4 (K-weighting, 400 ms blocks, -70 LUFS and -10 LU gates).
 */
object Master {
    const val TARGET_LUFS = -14.0
    const val CEILING = 0.891f            // -1 dBFS

    /** A biquad section (RBJ cookbook), run over a whole signal. */
    class Biquad(private val b0: Double, private val b1: Double, private val b2: Double, private val a1: Double, private val a2: Double) {
        fun run(x: FloatArray): FloatArray {
            val y = FloatArray(x.size)
            var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
            for (i in x.indices) {
                val v = x[i].toDouble()
                val o = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1; x1 = v; y2 = y1; y1 = o
                y[i] = o.toFloat()
            }
            return y
        }

        companion object {
            private fun make(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
                Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

            fun highPass(fc: Double, q: Double, rate: Int): Biquad {
                val w = 2 * PI * fc / rate; val c = cos(w); val al = sin(w) / (2 * q)
                return make((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al)
            }

            fun peaking(fc: Double, q: Double, db: Double, rate: Int): Biquad {
                val a = 10.0.pow(db / 40); val w = 2 * PI * fc / rate; val c = cos(w); val al = sin(w) / (2 * q)
                return make(1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a)
            }

            fun highShelf(fc: Double, q: Double, db: Double, rate: Int): Biquad {
                val a = 10.0.pow(db / 40); val w = 2 * PI * fc / rate; val c = cos(w); val al = sin(w) / (2 * q); val s = 2 * sqrt(a) * al
                return make(a * ((a + 1) + (a - 1) * c + s), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - s),
                    (a + 1) - (a - 1) * c + s, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - s)
            }
        }
    }

    /** Integrated loudness (LUFS) of a mono signal; -70 or below for silence. */
    fun loudness(x: FloatArray, rate: Int): Double {
        // K-weighting: the head's high shelf (+4 dB above ~1.5 kHz) and a 38 Hz high-pass (as pyloudnorm).
        val k = Biquad.highPass(38.0, 0.5, rate).run(Biquad.highShelf(1500.0, 1 / sqrt(2.0), 4.0, rate).run(x))
        val block = (0.4 * rate).toInt(); val hop = block / 4
        if (k.size < block) return blockLoudness(meanSquare(k, 0, k.size))
        val z = ArrayList<Double>()
        var s = 0
        while (s + block <= k.size) { z.add(meanSquare(k, s, s + block)); s += hop }
        val abs = z.filter { blockLoudness(it) > -70.0 }
        if (abs.isEmpty()) return -70.0
        val rel = blockLoudness(abs.average()) - 10.0
        val gated = abs.filter { blockLoudness(it) > rel }
        return blockLoudness((if (gated.isEmpty()) abs else gated).average())
    }

    private fun meanSquare(x: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return s / max(1, to - from)
    }

    private fun blockLoudness(z: Double) = -0.691 + 10 * log10(z + 1e-12)

    /** Equalizer for speech: rumble out, less box, more presence (and a little air when the rate allows it). */
    fun eq(x: FloatArray, rate: Int): FloatArray {
        var y = Biquad.highPass(80.0, 0.707, rate).run(x)
        y = Biquad.peaking(300.0, 1.0, -2.0, rate).run(y)
        y = Biquad.peaking(3500.0, 0.9, 2.5, rate).run(y)
        if (rate >= 32000) y = Biquad.highShelf(10000.0, 0.707, 1.5, rate).run(y)
        return y
    }

    /** Feed-forward compressor: RMS detector, soft knee. */
    fun compress(x: FloatArray, rate: Int, thresholdDb: Double = -20.0, ratio: Double = 3.0, kneeDb: Double = 6.0,
                 attackMs: Double = 5.0, releaseMs: Double = 120.0): FloatArray {
        val rmsA = exp(-1.0 / (0.010 * rate))
        val att = exp(-1.0 / (attackMs / 1000 * rate)); val rel = exp(-1.0 / (releaseMs / 1000 * rate))
        var ms = 0.0; var gr = 0.0          // gain reduction in dB (>= 0)
        val y = FloatArray(x.size)
        for (i in x.indices) {
            val v = x[i].toDouble()
            ms = rmsA * ms + (1 - rmsA) * v * v
            val lvl = 10 * log10(ms + 1e-12)
            val over = lvl - thresholdDb
            val target = when {
                over <= -kneeDb / 2 -> 0.0
                over >= kneeDb / 2 -> over * (1 - 1 / ratio)
                else -> (1 - 1 / ratio) * (over + kneeDb / 2).pow(2) / (2 * kneeDb)
            }
            gr = if (target > gr) att * gr + (1 - att) * target else rel * gr + (1 - rel) * target
            y[i] = (v * 10.0.pow(-gr / 20)).toFloat()
        }
        return y
    }

    /** Look-ahead peak limiter: no sample above [ceiling]; the gain dips just before a peak and recovers smoothly. */
    fun limit(x: FloatArray, rate: Int, ceiling: Float = CEILING, lookaheadMs: Double = 5.0, releaseMs: Double = 60.0): FloatArray {
        val la = max(1, (lookaheadMs / 1000 * rate).toInt())
        val need = FloatArray(x.size) { val a = abs(x[it]); if (a > ceiling) ceiling / a else 1f }
        // The smallest gain needed anywhere in the next [la] samples (sliding minimum, monotonic deque).
        val minAhead = FloatArray(x.size)
        val dq = IntArray(x.size + 1); var h = 0; var t = 0
        var j = 0
        for (i in x.indices) {
            while (j < x.size && j <= i + la) {
                while (t > h && need[dq[t - 1]] >= need[j]) t--
                dq[t++] = j; j++
            }
            while (dq[h] < i) h++
            minAhead[i] = need[dq[h]]
        }
        val rel = exp(-1.0 / (releaseMs / 1000 * rate)).toFloat()
        val down = exp(-1.0 / (la / 2.0)).toFloat()
        val y = FloatArray(x.size)
        var g = 1f
        for (i in x.indices) {
            val target = minAhead[i]
            g = if (target < g) down * g + (1 - down) * target else rel * g + (1 - rel) * target
            g = min(g, need[i])               // never let a peak through
            y[i] = x[i] * g
        }
        return y
    }

    /** Gain so that [x] measures [lufs]; at most +24 dB (a near-silent file is not blown up). */
    fun normalize(x: FloatArray, rate: Int, lufs: Double = TARGET_LUFS): FloatArray {
        val now = loudness(x, rate)
        if (now <= -69.0) return x
        val g = 10.0.pow(min(24.0, lufs - now) / 20).toFloat()
        return FloatArray(x.size) { x[it] * g }
    }

    /** The whole chain on a voice recording. */
    fun studio(x: FloatArray, rate: Int): FloatArray {
        if (x.isEmpty()) return x
        val c = compress(eq(x, rate), rate)
        // Limiting lowers the loudness a little; one correction pass gets it back within ~0.3 LU.
        var y = limit(normalize(c, rate), rate)
        val off = TARGET_LUFS - loudness(y, rate)
        if (abs(off) > 0.3) y = limit(FloatArray(y.size) { y[it] * 10.0.pow(off / 20).toFloat() }, rate)
        return y
    }
}
