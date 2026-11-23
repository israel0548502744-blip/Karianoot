package com.karianoot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class MixerTest {
    @Test fun musicSitsUnderTheVoiceAndFades() {
        val rate = 22050
        val voice = FloatArray(rate * 3) { if (it in rate..2 * rate) 0.5f * sin(2 * PI * 200 * it / rate).toFloat() else 0f }
        val music = FloatArray(44100) { 0.9f * sin(2 * PI * 440 * it / 44100).toFloat() }   // 1 s at 44.1 kHz: loops 4-5 times
        val out = Mixer.mix(voice, rate, music, 44100, level = 0.15f)
        assertEquals(voice.size + (1.5f * rate).toInt(), out.size)
        assertTrue(abs(out[0]) < 1e-3f)                                     // fades in from silence
        assertTrue(abs(out.last()) < 0.01f)                                  // fades out at the end
        val mid = out.slice(rate / 2 + 2 * rate until rate / 2 + 2 * rate + 2000).maxOf { abs(it) }
        assertTrue("music level away from the voice: $mid", mid in 0.12f..0.16f)
        assertTrue(out.maxOf { abs(it) } <= 0.95f + 1e-6f)
        assertEquals(22050, Mixer.resample(FloatArray(44100), 44100, 22050).size)
    }

    @Test fun duckingRaisesMusicInPausesOnly() {
        val rate = 22050
        // Speech 0-1 s, a 1.5 s pause, speech 2.5-3.5 s.
        val voice = FloatArray((rate * 3.5).toInt()) { i -> if (i < rate || i >= (2.5 * rate).toInt()) 0.5f * sin(2 * PI * 200 * i / rate).toFloat() else 0f }
        val g = Mixer.duckCurve(voice, rate, voice.size + rate, 2.2f)
        assertEquals(1f, g[rate / 2], 0.01f)                        // under speech: the set level
        assertTrue("open in the pause: ${g[(2.0 * rate).toInt()]}", g[(2.0 * rate).toInt()] > 1.8f)
        assertEquals(1f, g[(2.5 * rate).toInt() + 200], 0.05f)      // ducked again as the words start
        assertTrue(g[(2.5 * rate).toInt() - (0.03 * rate).toInt()] < 1.6f)   // already going down just before them
        assertTrue(g.last() > 1.8f)                                   // after the voice
        val out = Mixer.mix(voice, rate, FloatArray(44100) { 0.9f * sin(2 * PI * 440 * it / 44100).toFloat() }, 44100, level = 0.15f, duck = true)
        assertTrue(out.maxOf { abs(it) } <= 0.95f + 1e-6f)
    }
}
