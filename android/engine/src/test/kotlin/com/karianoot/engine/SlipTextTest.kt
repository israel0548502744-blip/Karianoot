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

    @Test fun rereadsNumbersFromTheirBoxes() {
        val heb = """<div class='ocr_page'><span class='ocr_line' id='l1' title="bbox 100 100 900 150">
            <span class='ocrx_word' id='w1' title='bbox 700 100 900 150; x_wconf 92'>ארסנל</span>
            <span class='ocrx_word' id='w2' title='bbox 650 100 680 150; x_wconf 90'>-</span>
            <span class='ocrx_word' id='w3' title='bbox 400 100 620 150; x_wconf 85'>צ&#39;לסי</span></span>
            <span class='ocr_line' id='l2' title="bbox 100 200 900 250">
            <span class='ocrx_word' id='w4' title='bbox 850 200 880 250; x_wconf 40'>י</span>
            <span class='ocrx_word' id='w5' title='bbox 100 200 300 250; x_wconf 40'>בםסםסם</span></span></div>"""
        val ws = SlipText.hocrWords(heb)
        assertEquals(5, ws.size)
        val text = SlipText.text(ws) { w -> if (w.x == 850) "1" else "2.10" }
        assertEquals("ארסנל - צ'לסי\n1 2.10", text)
        val p = SlipText.parse(text)
        assertEquals("ניצחון ארסנל", p.single().pick); assertEquals(2.10f, p.single().odds!!, 1e-4f)
    }
}
