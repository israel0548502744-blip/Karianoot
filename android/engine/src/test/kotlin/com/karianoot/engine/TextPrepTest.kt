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
        assertEquals("אֶם אֶס אשדוד נגד בני יהודה", TextPrep.apply("מ.ס. אשדוד נגד בני יהודה"))
        assertEquals("הוָאר פסל את השער", TextPrep.apply("הוי.איי.אר פסל את השער"))
        assertEquals("בדיקת וָאר ארוכה", TextPrep.apply("בדיקת וי.איי.אר. ארוכה"))
        assertEquals("הפועל עכו בליגה אָלֶף", TextPrep.apply("הפועל עכו בליגה א'"))
        assertEquals("ליגה גִּימֶל צפון", TextPrep.apply("ליגה ג' צפון"))
        assertEquals("ג'ונס", TextPrep.apply("ג'ונס"))
    }

    @Test fun oddsMoneyAndPlus() {
        assertEquals("ביחס אחד אפס חמש", TextPrep.apply("ביחס 1.05"))
        assertEquals("יחס שתיים אפס שמונה, לא 2.8", TextPrep.apply("יחס 2.08, לא 2.8"))
        assertEquals("ביחס 1.85", TextPrep.apply("ביחס 1.85"))
        assertEquals("1.5 ואחד אפס חמש", TextPrep.apply("1.5 ו-1.05"))
        assertEquals("שימו 100 שקל ותקבלו 850 שקל", TextPrep.apply("שימו 100 ש\"ח ותקבלו 850 ש״ח"))
        assertEquals("50 שקל על ניצחון", TextPrep.apply("₪50 על ניצחון"))
        assertEquals("רק 20 שקל", TextPrep.apply("רק 20₪"))
        assertEquals("דקה 90 פלוס 3", TextPrep.apply("דקה 90+3"))
        assertEquals("הנדיקפ פלוס 1.5", TextPrep.apply("הנדיקפ +1.5"))
        assertEquals("אני הולך על איקס.", TextPrep.apply("אני הולך על X."))
        assertEquals("אחת איקס שתיים, ו-איקס", TextPrep.apply("1 X 2, ו-X"))
    }
}
