package com.karianoot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptWriterTest {
    private val ms = listOf(
        ScriptWriter.Match("ארסנל נגד צ'לסי", "ניצחון ארסנל", 2.10f, "ארסנל ניצחה ארבעה מחמשת המשחקים האחרונים בבית"),
        ScriptWriter.Match("ריאל מדריד נגד ברצלונה", "שתי הקבוצות מבקיעות", 1.75f),
        ScriptWriter.Match("מכבי חיפה נגד הפועל באר שבע", "תיקו", 3.40f),
    )

    @Test fun everyStyleAndSeedReadsWell() {
        for (style in ScriptWriter.Style.entries) for (seed in 0 until 20) {
            val s = ScriptWriter.write(ms, style, seed)
            for (m in ms) { assertTrue(s, s.contains(m.match)); assertTrue(s, s.contains(m.pick)) }
            assertTrue(s, s.contains("2.10") && s.contains("1.75") && s.contains("3.40"))
            assertTrue(s, s.contains("12.50"))                      // 2.10 x 1.75 x 3.40
            assertTrue(s, !s.contains("%") && !s.contains("..") && !s.contains(" ."))
            assertEquals(s, ms.size + 3, s.lines().size)           // opener, one line per match, total, closer
        }
        println(ScriptWriter.write(ms, ScriptWriter.Style.HYPE, 1))
        assertNotEquals(ScriptWriter.write(ms, seed = 1), ScriptWriter.write(ms, seed = 2))
    }

    @Test fun oneMatchNoOdds() {
        val s = ScriptWriter.write(listOf(ScriptWriter.Match("אינטר נגד מילאן", "ניצחון אינטר", null)), ScriptWriter.Style.CALM, 3)
        assertTrue(s, s.contains("המשחק של הערב: אינטר נגד מילאן. ") && !s.contains("יחס כולל"))
    }
}
