package com.karianoot.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

class WavTest {
    @Test fun writeThenReadGivesTheSameSamples() {
        val a = FloatArray(44100) { (0.5 * sin(it * 0.01)).toFloat() }
        val f = File.createTempFile("k", ".wav")
        Wav.write(f, a, 44100)
        val (b, rate) = Wav.read(f)
        assertEquals(44100, rate)
        assertEquals(a.size, b.size)
        for (i in a.indices) assert(abs(a[i] - b[i]) < 1e-4f)
        f.delete()
    }
}
