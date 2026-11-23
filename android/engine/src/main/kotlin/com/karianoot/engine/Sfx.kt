package com.karianoot.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Football sound effects, made by the app itself from oscillators and noise (no recordings, so no licence question):
 * a referee's whistle, a final whistle, a cheering crowd, an air horn, a swoosh for transitions, a drum roll with a hit,
 * and a "ding". Every effect is peak-normalised to [LEVEL] so it sits under a narration at -14 LUFS without jumping out.
 */
object Sfx {
    const val LEVEL = 0.6f

    /** Name the user writes in braces in the text ({שריקה}) -> effect. */
    val NAMES = linkedMapOf(
        "שריקה" to "whistle", "שריקת סיום" to "final", "קהל" to "crowd", "צופר" to "horn",
        "מעבר" to "swoosh", "תופים" to "drums", "דינג" to "ding",
    )

    fun make(name: String, rate: Int, seed: Int = 7): FloatArray {
        val r = Random(seed)
        val x = when (NAMES[name] ?: name) {
            "whistle" -> whistle(rate, 0.75, r)
            "final" -> concat(rate, whistle(rate, 0.28, r), 0.14, whistle(rate, 0.28, r), 0.14, whistle(rate, 1.05, r))
            "crowd" -> crowd(rate, 2.6, r)
            "horn" -> concat(rate, horn(rate, 0.55), 0.12, horn(rate, 0.9))
            "swoosh" -> swoosh(rate, 0.55, r)
            "drums" -> drums(rate, r)
            "ding" -> ding(rate)
            else -> FloatArray(0)
        }
        return peak(x, LEVEL)
    }

    private fun peak(x: FloatArray, to: Float): FloatArray {
        val p = x.maxOfOrNull { abs(it) } ?: return x
        if (p < 1e-9f) return x
        val k = to / p
        return FloatArray(x.size) { x[it] * k }
    }

    private fun concat(rate: Int, vararg parts: Any): FloatArray {
        val list = parts.map { if (it is FloatArray) it else FloatArray(((it as Double) * rate).toInt()) }
        val out = FloatArray(list.sumOf { it.size })
        var p = 0
        for (a in list) { a.copyInto(out, p); p += a.size }
        return out
    }

    /** Attack / release envelope (seconds), raised-cosine edges. */
    private fun env(i: Int, n: Int, rate: Int, attack: Double, release: Double): Float {
        val a = (attack * rate).toInt().coerceAtLeast(1); val rl = (release * rate).toInt().coerceAtLeast(1)
        return when {
            i < a -> (0.5 - 0.5 * kotlin.math.cos(PI * i / a)).toFloat()
            i > n - rl -> (0.5 - 0.5 * kotlin.math.cos(PI * (n - i).coerceAtLeast(0) / rl)).toFloat()
            else -> 1f
        }
    }

    /** A pea whistle: ~2.9 kHz, the pea rattling it at ~32 Hz (amplitude and a little pitch), some breath noise. */
    fun whistle(rate: Int, seconds: Double, r: Random): FloatArray {
        val n = (seconds * rate).toInt()
        val f0 = min(2900.0, rate * 0.4)
        var ph = 0.0
        val bp = Resonator(f0, 0.08, rate)
        return FloatArray(n) { i ->
            val t = i.toDouble() / rate
            val trill = sin(2 * PI * 32 * t)
            val f = f0 + 70 * trill + 25 * sin(2 * PI * 5 * t)
            ph += 2 * PI * f / rate
            val tone = sin(ph) + 0.18 * sin(2 * ph)
            val am = 0.62 + 0.38 * trill
            val breath = bp.next(r.nextDouble(-1.0, 1.0)) * 0.35
            ((tone * am + breath) * env(i, n, rate, 0.015, 0.05)).toFloat()
        }
    }

    /** A crowd roaring: many noise bands swelling and falling, with slow random surges, through a small "stadium" reverb. */
    fun crowd(rate: Int, seconds: Double, r: Random): FloatArray {
        val n = (seconds * rate).toInt()
        val bands = listOf(350.0, 600.0, 900.0, 1300.0, 1900.0, 2600.0).filter { it < rate * 0.45 }.map { Resonator(it, 0.6, rate) }
        val gains = doubleArrayOf(1.0, 0.95, 0.85, 0.65, 0.45, 0.3)
        // Each band breathes on its own (crowds are many people, not one noise).
        val lfo = DoubleArray(bands.size) { 2.5 + r.nextDouble() * 4 }
        val lph = DoubleArray(bands.size) { r.nextDouble() * 2 * PI }
        // "Wooo" voices: gliding tones under the noise.
        val woo = List(5) { doubleArrayOf(380.0 + r.nextDouble() * 500, 0.0, r.nextDouble() * 0.6, 0.6 + r.nextDouble() * 1.2) }
        val dry = FloatArray(n) { i ->
            val t = i.toDouble() / rate
            val swell = min(1.0, t / 0.35) * exp(-max(0.0, t - 0.6) / 1.1)
            var s = 0.0
            for (b in bands.indices) s += bands[b].next(r.nextDouble(-1.0, 1.0)) * gains[b] * (0.7 + 0.3 * sin(2 * PI * lfo[b] * t + lph[b]))
            for (w in woo) {
                val local = t - w[2]
                if (local in 0.0..w[3]) {
                    w[1] += 2 * PI * (w[0] * (1 + 0.25 * local / w[3]) + 6 * sin(2 * PI * 5.5 * t)) / rate
                    s += 0.12 * sin(w[1]) * sin(PI * local / w[3])
                }
            }
            (s * swell * env(i, n, rate, 0.02, 0.4)).toFloat()
        }
        return reverb(dry, rate, 0.35)
    }

    /** A stadium air horn: three detuned saw-like tones (harmonics up to ~4 kHz), a quick attack and a short fall. */
    fun horn(rate: Int, seconds: Double): FloatArray {
        val n = (seconds * rate).toInt()
        val notes = doubleArrayOf(311.0, 392.0, 466.0)
        val ph = DoubleArray(notes.size)
        return FloatArray(n) { i ->
            val t = i.toDouble() / rate
            var s = 0.0
            for (k in notes.indices) {
                val f = notes[k] * (1 + 0.004 * sin(2 * PI * 6 * t + k)) * (if (t < 0.04) 0.94 + 1.5 * t else 1.0)
                ph[k] += 2 * PI * f / rate
                var h = 1
                while (h * f < min(4000.0, rate * 0.45)) { s += sin(h * ph[k]) / h; h++ }
            }
            (s * env(i, n, rate, 0.02, 0.09)).toFloat()
        }
    }

    /** A transition swoosh: noise through a band-pass that sweeps up, loudest in the middle. */
    fun swoosh(rate: Int, seconds: Double, r: Random): FloatArray {
        val n = (seconds * rate).toInt()
        val bp = Resonator(300.0, 0.5, rate)
        return FloatArray(n) { i ->
            val p = i.toDouble() / n
            bp.tune(300.0 * Math.pow(min(4500.0, rate * 0.4) / 300.0, p), 0.5)
            (bp.next(r.nextDouble(-1.0, 1.0)) * sin(PI * p) * sin(PI * p)).toFloat()
        }
    }

    /** A snare roll that speeds up and swells for 1.3 s, then a deep hit. */
    fun drums(rate: Int, r: Random): FloatArray {
        val roll = 1.3; val n = ((roll + 1.2) * rate).toInt()
        val out = FloatArray(n)
        var t = 0.0
        val snare = Resonator(1800.0, 0.9, rate)
        while (t < roll) {
            val start = (t * rate).toInt(); val len = (0.05 * rate).toInt()
            val amp = 0.35 + 0.65 * t / roll
            for (k in 0 until len) if (start + k < n) out[start + k] += (snare.next(r.nextDouble(-1.0, 1.0)) * amp * exp(-k / (0.012 * rate))).toFloat()
            t += 0.11 - 0.07 * t / roll
        }
        val hit = (roll * rate).toInt()
        var ph = 0.0
        for (k in 0 until n - hit) {
            val tt = k.toDouble() / rate
            ph += 2 * PI * (55 + 60 * exp(-tt / 0.05)) / rate
            out[hit + k] += ((sin(ph) * 1.4 + r.nextDouble(-1.0, 1.0) * 0.5 * exp(-tt / 0.03)) * exp(-tt / 0.35)).toFloat()
        }
        return reverb(out, rate, 0.2)
    }

    /** A bright two-note "ding" (G6 then C7), bell-like decay. */
    fun ding(rate: Int): FloatArray {
        val n = (1.1 * rate).toInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() / rate
            var s = sin(2 * PI * 1568 * t) * exp(-t / 0.25) + 0.3 * sin(2 * PI * 3136 * t) * exp(-t / 0.12)
            val t2 = t - 0.09
            if (t2 > 0) s += sin(2 * PI * 2093 * t2) * exp(-t2 / 0.4) + 0.25 * sin(2 * PI * 4186 * t2) * exp(-t2 / 0.15)
            (s * env(i, n, rate, 0.002, 0.05)).toFloat()
        }
    }

    /** A small Schroeder reverb (4 combs + 2 all-passes), [wet] mixed in. */
    fun reverb(x: FloatArray, rate: Int, wet: Double): FloatArray {
        val combs = intArrayOf(1557, 1617, 1491, 1422).map { (it * rate / 44100.0).toInt().coerceAtLeast(1) }
        val tail = (0.6 * rate).toInt()
        val n = x.size + tail
        val y = DoubleArray(n)
        for (d in combs) {
            val buf = DoubleArray(d); var p = 0
            for (i in 0 until n) {
                val v = if (i < x.size) x[i].toDouble() else 0.0
                val o = buf[p]; buf[p] = v + o * 0.78; p = (p + 1) % d
                y[i] += o / combs.size
            }
        }
        var z = y
        for (d0 in intArrayOf(225, 556)) {
            val d = (d0 * rate / 44100.0).toInt().coerceAtLeast(1)
            val buf = DoubleArray(d); var p = 0
            val src = z
            z = DoubleArray(n) { i -> val b = buf[p]; val v = src[i]; val o = -v + b; buf[p] = v + b * 0.5; p = (p + 1) % d; o }
        }
        return FloatArray(n) { i -> ((if (i < x.size) x[i] else 0f) * (1 - wet) + z[i] * wet).toFloat() }
    }

    /** A two-pole band-pass (resonator) at [f] Hz with bandwidth [bw] (fraction of f). */
    private class Resonator(f: Double, bw: Double, private val rate: Int) {
        private var a1 = 0.0; private var a2 = 0.0; private var g = 0.0
        private var y1 = 0.0; private var y2 = 0.0
        init { tune(f, bw) }
        fun tune(f: Double, bw: Double) {
            val rr = exp(-PI * bw * f / rate)
            a1 = 2 * rr * kotlin.math.cos(2 * PI * f / rate); a2 = -rr * rr; g = 1 - rr
        }
        fun next(x: Double): Double { val y = g * x + a1 * y1 + a2 * y2; y2 = y1; y1 = y; return y }
    }
}
