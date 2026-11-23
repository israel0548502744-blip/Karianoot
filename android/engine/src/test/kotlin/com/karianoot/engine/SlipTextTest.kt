package com.karianoot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlipTextTest {
    @Test fun readsASlip() {
        val ocr = """
            הטופס שלי
            ארסנל - צ'לסי
            1  2.10
            ריאל מדריד - ברצלונה
            שתי הקבוצות יבקיעו
            1.75
            מכבי חיפה נגד הפועל באר שבע
            X | 3,40
            באיירן - דורטמונד
            מעל 2.5 שערים 1.60
            יחס כולל 22.94
        """.trimIndent()
        val p = SlipText.parse(ocr)
        assertEquals(listOf("ארסנל נגד צ'לסי", "ריאל מדריד נגד ברצלונה", "מכבי חיפה נגד הפועל באר שבע", "באיירן נגד דורטמונד"), p.map { it.match })
        assertEquals(listOf("ניצחון ארסנל", "שתי הקבוצות יבקיעו", "תיקו", "מעל 2.5 שערים"), p.map { it.pick })
        assertEquals(listOf(2.10f, 1.75f, 3.40f, 1.60f), p.map { it.odds })
        val s = SlipText.script(p)
        assertTrue(s, s.contains("המשחק הראשון: ארסנל נגד צ'לסי. אני הולך על ניצחון ארסנל, ביחס 2.10."))
        assertTrue(s, s.contains("ביחס כולל של 19.99"))
    }
}
