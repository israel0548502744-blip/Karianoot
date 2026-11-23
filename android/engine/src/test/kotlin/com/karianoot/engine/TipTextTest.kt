package com.karianoot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TipTextTest {
    @Test fun findsTipsInAScript() {
        val s = "ערב טוב לכל האוהדים! המשחק הראשון: ארסנל נגד צ'לסי. אני הולך על ניצחון של ארסנל, ביחס של 2.10. " +
            "ועכשיו למשחק השני: ריאל מדריד מול ברצלונה. פה אני ממליץ על שתי הקבוצות מבקיעות, והיחס 1.75.\nהערב גם מכבי חיפה נגד הפועל באר שבע."
        val t = TipText.parse(s)
        assertEquals(listOf("ארסנל נגד צ'לסי", "ריאל מדריד נגד ברצלונה"), t.take(2).map { it.match })
        assertTrue(t[2].match, t[2].match.startsWith("מכבי חיפה נגד הפועל באר"))
        assertEquals("ניצחון של ארסנל", t[0].pick); assertEquals(2.10f, t[0].odds!!, 1e-4f)
        assertEquals("שתי הקבוצות מבקיעות", t[1].pick); assertEquals(1.75f, t[1].odds!!, 1e-4f)
    }

    @Test fun weeklySummary() {
        val s = TipText.summary(10, 7, 4.35f, listOf(TipText.Pick("ארסנל נגד צ'לסי", "ניצחון של ארסנל", 2.1f), TipText.Pick("ריאל נגד ברצלונה", "", 3.4f)))
        assertTrue(s, s.startsWith("איזה שבוע! מתוך 10 טיפים, פגענו ב-7. זה 70 אחוז הצלחה."))
        assertTrue(s, s.contains("ריאל נגד ברצלונה ביחס 3.40, ארסנל נגד צ'לסי, ניצחון של ארסנל ביחס 2.10."))
        assertTrue(s, s.contains("הרוויח 4.35 יחידות"))
        assertTrue(TipText.summary(0, 0, 0f, emptyList()).contains("עוד לא נסגרו"))
    }
}
