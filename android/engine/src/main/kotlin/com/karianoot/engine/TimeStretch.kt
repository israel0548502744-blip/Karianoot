package com.karianoot.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Faster or slower speech without changing the pitch (WSOLA: overlap-add of 40 ms frames, each taken where it best
 * continues the previous one). For Qwen, which has no speed control of its own.
 */
object TimeStretch {
    /** [speed] > 1 is faster (shorter). Returns [x] unchanged near 1. */
    fun stretch(x: FloatArray, speed: Float, rate: Int): FloatArray {
        if (kotlin.math.abs(speed - 1f) < 0.01f || x.size < rate / 10) return x
        val n = ((rate * 0.04).roundToInt() / 2) * 2          // frame
        val hs = n / 2                                         // output hop
        val tol = n / 4                                        // search range
        val win = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
        val outLen = (x.size / speed).roundToInt()
        val y = FloatArray(outLen + n)
        val w = FloatArray(outLen + n)
        var prev = 0          // input start of the previous frame
        var out = 0
        while (out + n <= y.size) {
            val nominal = (out * speed).roundToInt()
            var best = nominal.coerceIn(0, maxOf(0, x.size - n))
            if (out > 0) {
                // The input that would naturally follow the previous frame; pick the candidate that matches it best.
                val natural = prev + hs
                var bestScore = Double.NEGATIVE_INFINITY
                val lo = maxOf(0, nominal - tol); val hi = minOf(x.size - n, nominal + tol)
                var c = lo
                while (c <= hi) {
                    var s = 0.0
                    var k = 0
                    while (k < hs) {
                        val a = if (natural + k < x.size) x[natural + k] else 0f
                        s += a * x[c + k]
                        k += 2
                    }
                    if (s > bestScore) { bestScore = s; best = c }
                    c += 1
                }
            }
            if (best + n > x.size) break
            for (k in 0 until n) { y[out + k] += x[best + k] * win[k]; w[out + k] += win[k] }
            prev = best
            out += hs
        }
        return FloatArray(outLen) { i -> if (w[i] > 1e-3f) y[i] / w[i] else 0f }
    }
}
