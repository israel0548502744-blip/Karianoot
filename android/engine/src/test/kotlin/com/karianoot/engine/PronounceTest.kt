package com.karianoot.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class PronounceTest {
    private val fixes = listOf(Pronounce.Fix("היחס", "הייאחס"), Pronounce.Fix("ברצלונה", "בַּרְסֶלוֹנָה"))

    @Test fun replacesWholeWordsWithPrefixes() {
        assertEquals("הייאחס פה, והייאחס שם, ולהייאחס!", Pronounce.apply("היחס פה, והיחס שם, ולהיחס!", fixes))
        assertEquals("נגד בַּרְסֶלוֹנָה ולבַּרְסֶלוֹנָה", Pronounce.apply("נגד ברצלונה ולברצלונה", fixes))
    }

    @Test fun leavesOtherWordsAlone() {
        assertEquals("היחסים טובים", Pronounce.apply("היחסים טובים", fixes))
        assertEquals("abc", Pronounce.apply("abc", emptyList()))
    }

    @Test fun yachasGetsTheClearerSpelling() {
        assertEquals("tistaklˈu ʔˈal hajjˈaχas, vehajjˈaχas hajaχasˈim", BlueTts.fixIpa("tistaklˈu ʔˈal hajˈaχas, vehajˈaχas hajaχasˈim"))
    }

    @Test fun barcelonaGetsItsVowel() {
        assertEquals("lebaʁtselˈona", BlueTts.fixIpa("lebaʁtslˈona"))
    }
}
