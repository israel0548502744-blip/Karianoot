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
    }

    @Test fun otherWordsAreLeftAlone() {
        // Not whole words: a dictionary word inside a longer word, or a different word.
        assertEquals("תיקון", Lexicon.apply("תיקון"))
        assertEquals("מתיקות", Lexicon.apply("מתיקות"))
        assertEquals("ליונל", Lexicon.apply("ליונל"))
        assertEquals("ריאל מדריד", Lexicon.apply("ריאל מדריד"))
    }

    @Test fun prefixSounds() {
        assertEquals("ba", Lexicon.prefix("בה"))
        assertEquals("ule", Lexicon.prefix("ול"))
        assertEquals("uve", Lexicon.prefix("וב"))
        assertEquals("veha", Lexicon.prefix("וה"))
        assertEquals("ʃele", Lexicon.prefix("של"))
    }
}
