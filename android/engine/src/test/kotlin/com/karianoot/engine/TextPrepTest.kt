package com.karianoot.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class TextPrepTest {
    @Test fun bettingTerms() {
        assertEquals("הימור אחת איקס שתיים על המשחק", TextPrep.apply("הימור 1X2 על המשחק"))
        assertEquals("דאבל צ'אנס איקס שתיים", TextPrep.apply("דאבל צ'אנס X2"))
        assertEquals("הנדיקפ מינוס 1", TextPrep.apply("הנדיקפ -1"))
        assertEquals("ניצחון 3-1", TextPrep.apply("ניצחון 3-1"))
    }

    @Test fun scoresAreNotTimes() {
        assertEquals("ניצחון 2-1 בבית", TextPrep.apply("ניצחון 2:1 בבית"))
        assertEquals("בשעה 21:45", TextPrep.apply("בשעה 21:45"))
        assertEquals("בשעה 9:05", TextPrep.apply("בשעה 9:05"))
    }

    @Test fun abbreviationsAndAcronyms() {
        assertEquals("מכבי תל אביב נגד הפועל באר שבע", TextPrep.apply("מכבי ת\"א נגד הפועל ב\"ש"))
        assertEquals("פִּי אֶס גִ'י נגד מילאן", TextPrep.apply("PSG נגד מילאן"))
        assertEquals("ה-וָאר פסל את השער", TextPrep.apply("ה-VAR פסל את השער"))
        assertEquals("ליגת האלופות", TextPrep.apply("ליגת האלופות"))
    }
}
