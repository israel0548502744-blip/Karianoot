package com.karianoot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class CaptionsTest {
    @Test fun wordsFollowTheVoiceAndPausesFallBetweenThem() {
        val rate = 22050
        // "ערב טוב" 0.0-0.8 s, silence 0.8-1.4 s, "לכל האוהדים" 1.4-2.4 s.
        val audio = FloatArray(rate * 3) { i -> val t = i.toFloat() / rate; if (t < 0.8f || t in 1.4f..2.4f) (0.4 * sin(2 * PI * 180 * t)).toFloat() else 0f }
        val w = Captions.words(listOf(Captions.Span("עֶרֶב טוב, לכל האוהדים!", 0, (2.5 * rate).toInt())), audio, rate)
        assertEquals(listOf("ערב", "טוב,", "לכל", "האוהדים!"), w.map { it.text })
        for (i in 1 until w.size) assertTrue(w[i].start >= w[i - 1].end - 1e-3f)
        assertTrue("second word ends by the pause: ${w[1].end}", w[1].end <= 0.85f)
        assertTrue("third word starts after the pause: ${w[2].start}", w[2].start >= 1.38f)
        assertTrue(w.last().end <= 2.45f)
        val cards = Captions.cards(w)
        assertEquals(listOf("ערב טוב,", "לכל האוהדים!"), cards.map { it.text })
        val srt = Captions.srt(cards)
        assertTrue(srt.startsWith("1\n00:00:00,000 --> 00:00:0"))
        assertTrue(srt.contains("2\n00:00:01,"))
    }

    @Test fun digitsCountAsTheirWordsAndMarkersAreHidden() {
        assertTrue(Captions.weight("3.40") > Captions.weight("טוב"))
        assertEquals("ערב טוב לכולם", Captions.display("{שריקה} עֶרֶב טוב {הפסקה 2} לכולם"))
    }
}
