package com.karianoot.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class IpaHebrewTest {
    private fun show(ipa: String) = IpaHebrew.render(ipa).joinToString("·") { if (it.stressed) "[${it.text}]" else it.text }

    private fun nfc(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC)

    @Test fun readsBackAsHebrew() {
        for ((ipa, want) in listOf(
            "ʔˈaʁsenal" to "[אַרְ]·סֶ·נַל", "tˈeku" to "[טֶ]·קוּ", "ʃalˈom" to "שַׁ·[לוֹם]", "dʒˈenoa" to "[ג׳ֶ]·נוֹ·אַ",
            "ʔˈitalja" to "[אִי]·טַלְ·יַ", "tˈov" to "[טוֹב]", "ʔˈeʁev" to "[אֶ]·רֶב", "vˈeʃlo" to "[וֶשְׁ]·לוֹ", "lˈajptsiɡ" to "[לַיְ]·פְּצִיג", "mbapˈe" to "מְבַּ·[פֶּ]",
        )) assertEquals(ipa, nfc(want), show(ipa))
    }

    @Test fun stressChoices() {
        assertEquals(listOf("ʔˈaʁsenal", "ʔaʁsˈenal", "ʔaʁsenˈal"), IpaHebrew.stressVariants("ʔˈaʁsenal"))
        assertEquals(listOf("tˈeku", "tekˈu"), IpaHebrew.stressVariants("tˈeku"))
    }

    @Test fun lexiconTakesUserFixesWithPrefixes() {
        try {
            Lexicon.setUser(mapOf("ארסנל" to "ʔaʁsenˈal", "שלום" to "ʃˈalom"))
            assertEquals("leʔaʁsenˈal", Lexicon.apply("לארסנל"))
            assertEquals("ʃˈalom", Lexicon.apply("שלום"))
            // The fix is not applied as text: it waits for the lexicon step.
            assertEquals("שלום", Pronounce.apply("שלום", listOf(Pronounce.Fix("שלום", "ʃˈalom"))))
        } finally { Lexicon.setUser(emptyMap()) }
        assertEquals("ʔˈaʁsenal", Lexicon.apply("ארסנל"))
    }
}
