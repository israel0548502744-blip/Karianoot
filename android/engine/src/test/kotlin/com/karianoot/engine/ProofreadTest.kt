package com.karianoot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProofreadTest {
    @Test fun flagsOnlyRealMisses() {
        // Spelling variants and final letters are not misses; a dropped / different word is.
        assertTrue(Proofread.missed("הערב ארסנל נגד צ'לסי", "הערב ארסנל נגד צלסי").isEmpty())
        assertTrue(Proofread.missed("ביחס שלוש ארבעים", "ביחס 3.40").isEmpty())
        val m = Proofread.missed("אני הולך על תיקו ביחס גבוה", "אני הולך על טייקו ביחס גבוה")
        assertTrue(m.isEmpty() || m.single().ref == "תיקו")
        val dropped = Proofread.missed("ריאל מדריד מול ברצלונה הערב", "ריאל מדריד הערב")
        assertEquals(listOf("מול ברצלונה"), dropped.map { it.ref })
        val wrong = Proofread.missed("והיחס על ליברפול", "והיאס על כדורגל")
        assertEquals(listOf("ליברפול"), wrong.map { it.ref }.filter { it == "ליברפול" })
    }
}
