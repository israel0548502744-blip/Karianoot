package com.karianoot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** The pure-Kotlin parts of the Magpie engine: pieces, IPA cleanup, frame budget and its TakeCheck norms. */
class MagpieTtsTest {
    @Test fun piecesKeepSentencesAndTheWholeText() {
        val text = "ערב טוב לכל האוהדים! הערב יש לנו משחק ענק בין ריאל מדריד לברצלונה. תסתכלו על היחס, והיחס פה פשוט מטורף. " +
            "אני ממליץ לשים על ניצחון של ריאל, ושני שערים לפחות. מי שעוד לא הצטרף לקבוצה, זה הזמן."
        val p = MagpieTts.pieces(text)
        assertTrue(p.size >= 2)
        assertTrue(p.all { it.length <= BlueTts.CHUNK_CHARS })
        assertTrue(p.all { it.trimEnd().last() in ".!?" })            // whole sentences
        assertEquals(text.replace(" ", ""), p.joinToString("").replace(" ", ""))
    }

    @Test fun longSentenceIsCutAtCommasThenWords() {
        val clause = "והקבוצה הזאת משחקת כדורגל התקפי ומהיר מאוד"
        val long = List(6) { clause }.joinToString(", ") + "."
        val p = MagpieTts.pieces(long)
        assertTrue(p.size > 1 && p.all { it.length <= MagpieTts.MAX_PIECE_CHARS })
        assertEquals(long.replace(" ", ""), p.joinToString("").replace(" ", ""))
        val noComma = List(40) { "מילה" }.joinToString(" ") + "."
        assertTrue(MagpieTts.pieces(noComma).all { it.length <= BlueTts.CHUNK_CHARS })
    }

    @Test fun ipaIsLimitedToTheTokenizerSymbols() {
        assertEquals("ʁeˈal madˈʁid, ʃalˈom!", MagpieTts.clean("ʁeˈal  madˈʁid ;  ʃalˈom ! ☺"))
        assertEquals("ɡol", MagpieTts.clean("gol"))
        assertTrue(MagpieTts.clean("t͡s ʒ é").all { it in MagpieTts.SYMBOLS || it == ' ' })
    }

    @Test fun frameBudgetWithinTheModelLimit() {
        assertTrue(MagpieTts.maxFrames("a".repeat(100)) in 200..300)
        assertEquals(MagpieTts.MODEL_MAX_FRAMES, MagpieTts.maxFrames("a".repeat(1000)))
    }

    private fun take(rate: Int, vararg seg: Pair<Double, Double>): FloatArray {
        val out = ArrayList<Float>()
        for ((sec, amp) in seg) repeat((sec * rate).toInt()) { out.add((amp * sin(2 * PI * 220.0 * out.size / rate)).toFloat()) }
        return out.toFloatArray()
    }

    @Test fun magpieNormsAndQwenDefaultsUnchanged() {
        val r = 22050
        // 155 characters in 5 s = 31/s: a normal Magpie pace, too fast for Qwen.
        val fast = take(r, 2.5 to 0.5, 0.3 to 0.0, 2.2 to 0.5)
        assertFalse(TakeCheck.measure(fast, r, 155, TakeCheck.MAGPIE).broken)
        assertTrue(TakeCheck.measure(fast, r, 155).broken)
        // 11.5/s: Qwen keeps it, Magpie calls it a run-on.
        assertTrue(TakeCheck.measure(take(r, 4.0 to 0.5), r, 46, TakeCheck.MAGPIE).broken)
        assertFalse(TakeCheck.measure(take(r, 4.0 to 0.5), r, 46).broken)
        // A stall breaks either.
        assertTrue(TakeCheck.measure(take(r, 2.0 to 0.5, 1.5 to 0.0, 2.0 to 0.5), r, 80, TakeCheck.MAGPIE).broken)
    }
}
