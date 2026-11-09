package com.karianoot.engine

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Spots a Qwen take that went wrong, so it can be made again before the user hears it. Qwen writes audio frame by
 * frame and can, rarely, run on (repeat, babble, stall) or stop early (drop words). Measured on 150 good takes
 * (3 voices, greedy and sampled): 18.5 IPA characters per second of speech (15.8-21.9), and no pause inside a piece
 * longer than 0.6 s. Far outside that is a broken take.
 */
object TakeCheck {
    /** IPA characters per second of speech in good Qwen takes. */
    const val QWEN_RATE = 18.5f
    private const val SLOW = 11f        // slower than this: ran on (or stalled)
    private const val FAST = 30f        // faster than this: words were dropped
    private const val STALL_S = 1.2f    // a silence this long inside a piece is a stall

    class Report(val rate: Float, val longestPause: Float) {
        /** How far from a good take: 0 when fine, larger is worse. */
        val badness: Float get() = abs(log10(rate.coerceAtLeast(0.1f) / QWEN_RATE)) + if (longestPause >= STALL_S) longestPause else 0f
        val broken: Boolean get() = rate < SLOW || rate > FAST || longestPause >= STALL_S
    }

    /** Speech rate (IPA characters per second between the first and last sound) and the longest inner pause. */
    fun measure(w: FloatArray, sampleRate: Int, ipaLength: Int): Report {
        val h = sampleRate / 100
        val n = w.size / h
        if (n < 2 || ipaLength <= 0) return Report(QWEN_RATE, 0f)
        val e = DoubleArray(n) { i -> var s = 0.0; for (k in i * h until (i + 1) * h) s += w[k].toDouble() * w[k]; sqrt(s / h) }
        val peak = e.max()
        if (peak < 1e-6) return Report(0f, 0f)   // silence: nothing was said
        val loud = BooleanArray(n) { 20 * log10(e[it] / peak + 1e-12) > -38 }
        val first = loud.indexOfFirst { it }; val last = loud.indexOfLast { it }
        var longest = 0; var run = 0
        for (i in first..last) { if (!loud[i]) { run++; if (run > longest) longest = run } else run = 0 }
        val seconds = (last - first + 1) / 100f
        return Report(ipaLength / seconds, longest / 100f)
    }
}
