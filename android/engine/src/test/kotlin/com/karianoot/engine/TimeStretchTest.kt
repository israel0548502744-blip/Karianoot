package com.karianoot.engine

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

class TimeStretchTest {
    private fun crossings(a: FloatArray): Int { var n = 0; for (i in 1 until a.size) if (a[i - 1] < 0f && a[i] >= 0f) n++; return n }

    @Test fun fasterKeepsThePitch() {
        val rate = 24000
        val x = FloatArray(rate * 2) { (0.5 * sin(2 * PI * 200.0 * it / rate)).toFloat() }
        val y = TimeStretch.stretch(x, 1.25f, rate)
        assertEquals(x.size / 1.25, y.size.toDouble(), 2.0)
        // Pitch: zero crossings per second stay at ~200.
        val perSec = crossings(y.copyOfRange(2000, y.size - 2000)) / ((y.size - 4000).toDouble() / rate)
        assertEquals(200.0, perSec, 6.0)
    }

    @Test fun slowerKeepsThePitch() {
        val rate = 24000
        val x = FloatArray(rate) { (0.5 * sin(2 * PI * 150.0 * it / rate)).toFloat() }
        val y = TimeStretch.stretch(x, 0.8f, rate)
        assertEquals(x.size / 0.8, y.size.toDouble(), 2.0)
        val perSec = crossings(y.copyOfRange(2000, y.size - 2000)) / ((y.size - 4000).toDouble() / rate)
        assertEquals(150.0, perSec, 5.0)
    }

    /** Writes stretched copies of a real recording for listening / ASR (KARIANOOT_STRETCH = a wav file). */
    @Test fun stretchARecording() {
        val f = System.getenv("KARIANOOT_STRETCH")?.takeIf { it.isNotEmpty() }?.let(::File)
        assumeTrue(f != null && f.exists())
        val (x, rate) = Wav.read(f!!)
        for (s in listOf(0.9f, 1.15f, 1.3f)) {
            val t0 = System.nanoTime()
            val y = TimeStretch.stretch(x, s, rate)
            println("CHECK stretch $s: ${x.size} -> ${y.size} in ${(System.nanoTime() - t0) / 1_000_000} ms")
            Wav.write(File(f.parentFile, f.nameWithoutExtension + "_x$s.wav"), y, rate)
        }
    }
}
