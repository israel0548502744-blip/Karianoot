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

    /**
     * What a good take of one model looks like: its usual speech rate (IPA characters per second), slower than [slow]
     * = ran on or stalled, faster than [fast] = words dropped, an inner silence of [stall] seconds = a stall.
     */
    class Norms(val rate: Float, val slow: Float, val fast: Float, val stall: Float = 1.2f)

    val QWEN = Norms(QWEN_RATE, 11f, 30f)
    /** Magpie (NeMo Hebrew output, 30 pieces x 15 voices): 20.0 chars/s (18.9-24.1), inner pauses up to 0.47 s. */
    val MAGPIE = Norms(20f, 12f, 32f)

    class Report(val rate: Float, val longestPause: Float, val norms: Norms = QWEN) {
        /** How far from a good take: 0 when fine, larger is worse. */
        val badness: Float get() = abs(log10(rate.coerceAtLeast(0.1f) / norms.rate)) + if (longestPause >= norms.stall) longestPause else 0f
        val broken: Boolean get() = rate < norms.slow || rate > norms.fast || longestPause >= norms.stall
    }

    /** Speech rate (IPA characters per second between the first and last sound) and the longest inner pause. */
    fun measure(w: FloatArray, sampleRate: Int, ipaLength: Int, norms: Norms = QWEN): Report {
        val h = sampleRate / 100
        val n = w.size / h
        if (n < 2 || ipaLength <= 0) return Report(norms.rate, 0f, norms)
        val e = DoubleArray(n) { i -> var s = 0.0; for (k in i * h until (i + 1) * h) s += w[k].toDouble() * w[k]; sqrt(s / h) }
        val peak = e.max()
        if (peak < 1e-6) return Report(0f, 0f, norms)   // silence: nothing was said
        val loud = BooleanArray(n) { 20 * log10(e[it] / peak + 1e-12) > -38 }
        val first = loud.indexOfFirst { it }; val last = loud.indexOfLast { it }
        var longest = 0; var run = 0
        for (i in first..last) { if (!loud[i]) { run++; if (run > longest) longest = run } else run = 0 }
        val seconds = (last - first + 1) / 100f
        return Report(ipaLength / seconds, longest / 100f, norms)
    }
}
