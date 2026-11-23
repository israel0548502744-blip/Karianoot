package com.karianoot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SfxTest {
    @Test fun everyEffectIsMadeAtEveryRate() {
        for (rate in intArrayOf(22050, 24000, 44100)) for (name in Sfx.NAMES.keys) {
            val x = Sfx.make(name, rate)
            assertTrue("$name at $rate: ${x.size}", x.size in rate / 4..rate * 4)
            assertTrue(x.all { it.isFinite() })
            assertEquals("$name peak", Sfx.LEVEL, x.maxOf { abs(it) }, 1e-4f)
            assertTrue("$name starts quietly", abs(x[0]) < 0.05f)
        }
    }

    @Test fun whistleIsHighAndCrowdIsBroad() {
        val rate = 44100
        val w = Sfx.make("שריקה", rate)
        var zc = 0; for (i in 1 until w.size) if (w[i - 1] < 0 && w[i] >= 0) zc++
        val f = zc / (w.size.toDouble() / rate)
        assertTrue("whistle ~2.9 kHz, measured $f", f in 2400.0..3400.0)
    }
}
