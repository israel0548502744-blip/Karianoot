package com.karianoot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class MasterTest {
    private fun sine(amp: Double, f: Double, rate: Int, sec: Double) = FloatArray((rate * sec).toInt()) { (amp * sin(2 * PI * f * it / rate)).toFloat() }

    @Test fun loudnessMatchesBs1770() {
        // A full-scale 997 Hz sine is -3.01 LUFS; at 0.1 it is -23.0 (EBU test signals).
        for (rate in intArrayOf(22050, 24000, 44100, 48000)) {
            assertEquals(-3.01, Master.loudness(sine(1.0, 997.0, rate, 5.0), rate), 0.15)
            assertEquals(-23.0, Master.loudness(sine(0.1, 997.0, rate, 5.0), rate), 0.15)
        }
        assertTrue(Master.loudness(FloatArray(44100), 44100) <= -69.0)
    }

    /** Speech-like test signal: bursts of a few tones at uneven levels with pauses. */
    private fun fakeSpeech(rate: Int): FloatArray {
        val r = Random(3)
        val x = FloatArray(rate * 8)
        var t = 0
        while (t < x.size) {
            val len = (0.15 + r.nextDouble() * 0.4) * rate
            val amp = 0.05 + r.nextDouble() * 0.6
            val f = 120 + r.nextDouble() * 120
            for (k in 0 until len.toInt()) if (t + k < x.size) x[t + k] = (amp * (sin(2 * PI * f * k / rate) + 0.4 * sin(2 * PI * 3 * f * k / rate)) * sin(PI * k / len)).toFloat()
            t += len.toInt() + (0.05 + r.nextDouble() * 0.3 * rate).toInt()
        }
        return x
    }

    @Test fun studioHitsTargetWithoutClipping() {
        for (rate in intArrayOf(22050, 44100)) {
            for (scale in floatArrayOf(0.05f, 0.3f, 1f)) {
                val x = fakeSpeech(rate).map { it * scale }.toFloatArray()
                val y = Master.studio(x, rate)
                assertEquals(x.size, y.size)
                assertEquals("rate $rate scale $scale", Master.TARGET_LUFS, Master.loudness(y, rate), 0.6)
                assertTrue(y.maxOf { abs(it) } <= Master.CEILING + 1e-4f)
            }
        }
    }

    @Test fun limiterStopsPeaksAndLeavesQuietAlone() {
        val rate = 24000
        val x = sine(0.3, 300.0, rate, 1.0)
        for (i in 10000 until 10100) x[i] = 1.6f * (if (i % 2 == 0) 1 else -1)
        val y = Master.limit(x, rate)
        assertTrue(y.maxOf { abs(it) } <= Master.CEILING + 1e-6f)
        // Far from the peak the signal is untouched.
        for (i in 0 until 5000) assertEquals(x[i], y[i], 1e-4f)
    }

    @Test fun highPassRemovesRumble() {
        val rate = 44100
        val y = Master.eq(sine(0.5, 30.0, rate, 2.0), rate)
        assertTrue(y.drop(rate).maxOf { abs(it) } < 0.1f)
    }
}
