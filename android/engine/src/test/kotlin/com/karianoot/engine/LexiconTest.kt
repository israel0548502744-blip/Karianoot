package com.karianoot.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class LexiconTest {
    @Test fun wordsAndPrefixes() {
        assertEquals("הימור על tˈeku", Lexicon.apply("הימור על תיקו"))
        assertEquals("ʔˈaʁsenal נגד באיירן", Lexicon.apply("ארסנל נגד באיירן").replace("bˈajeʁn", "באיירן"))
        assertEquals("leʔˈaʁsenal", Lexicon.apply("לארסנל"))
        assertEquals("betˈeku", Lexicon.apply("ב-תיקו"))
        assertEquals("uleʔembˈape", Lexicon.apply("ולמבאפה"))
        assertEquals("hapʁemjˈeʁ ליג", Lexicon.apply("הפרמייר ליג"))
        assertEquals("behˈendikep", Lexicon.apply("בהנדיקפ"))
        assertEquals("tʃuʔamˈeni", Lexicon.apply("צ׳ואמני"))
        assertEquals("שער של hˈeʁi kˈejn", Lexicon.apply("שער של הארי קיין"))
        assertEquals("leʔˈunjon beʁlˈin", Lexicon.apply("לאוניון ברלין"))
    }

    @Test fun otherWordsAreLeftAlone() {
        // Not whole words: a dictionary word inside a longer word, or a different word.
        assertEquals("תיקון", Lexicon.apply("תיקון"))
        assertEquals("מתיקות", Lexicon.apply("מתיקות"))
        assertEquals("ליונל", Lexicon.apply("ליונל"))
        assertEquals("ריאל מדריד", Lexicon.apply("ריאל מדריד"))
        // Ordinary Hebrew words that look like names are not in the lexicon (קומו = "get up", לאנס).
        assertEquals("קומו והצטרפו", Lexicon.apply("קומו והצטרפו"))
        assertEquals("הוא בודה סיפורים", Lexicon.apply("הוא בודה סיפורים"))
        // Prefixed forms of keys that are other words: מליון (million), השתיקו (silenced), וואן (one).
        assertEquals("מליון שקל", Lexicon.apply("מליון שקל"))
        assertEquals("במליון", Lexicon.apply("במליון"))
        assertEquals("השתיקו את הקהל", Lexicon.apply("השתיקו את הקהל"))
        assertEquals("נאמבר וואן", Lexicon.apply("נאמבר וואן"))
        assertEquals("lelijˈon", Lexicon.apply("לליון"))
        assertEquals("betˈeku", Lexicon.apply("בתיקו"))
        // Ordinary words behind names added in the big lexicon round: שמאלי (left, not ש+מאלי), מואר (lit, not
        // מ+ואר), כפפה (glove, not כ+פפה), מקוון (online), לטאה (lizard), מוטורס, שפירו.
        assertEquals("בצד השמאלי", Lexicon.apply("בצד השמאלי"))
        assertEquals("חדר מואר", Lexicon.apply("חדר מואר"))
        assertEquals("כפפה", Lexicon.apply("כפפה"))
        assertEquals("מקוון", Lexicon.apply("מקוון"))
        assertEquals("לטאה", Lexicon.apply("לטאה"))
        assertEquals("ג'נרל מוטורס", Lexicon.apply("ג'נרל מוטורס"))
        assertEquals("שפירו", Lexicon.apply("שפירו"))
    }

    @Test fun bigRoundNames() {
        assertEquals("ʔˈal nˈaseʁ", Lexicon.apply("אל-נאסר"))
        assertEquals("lˈuis skˈeli", Lexicon.apply("לואיס-סקלי"))
        assertEquals("luʔˈis", Lexicon.apply("לואיס"))
        assertEquals("את vˈaʁ", Lexicon.apply("את ואר"))
        assertEquals("lemˈali", Lexicon.apply("למאלי"))
        assertEquals("ʒuʔˈau", Lexicon.apply("ז'ואאו"))
        assertEquals("bundeslˈiɡa", Lexicon.apply("בונדסליגה"))
        assertEquals("של pˈepe", Lexicon.apply("של פפה"))
        assertEquals("mˈalmo", Lexicon.apply("מאלמו"))
    }

    @Test fun prefixSounds() {
        assertEquals("ba", Lexicon.prefix("בה"))
        assertEquals("ule", Lexicon.prefix("ול"))
        assertEquals("uve", Lexicon.prefix("וב"))
        assertEquals("veha", Lexicon.prefix("וה"))
        assertEquals("ʃele", Lexicon.prefix("של"))
    }
}
