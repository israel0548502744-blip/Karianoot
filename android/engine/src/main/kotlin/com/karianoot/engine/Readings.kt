package com.karianoot.engine

/** "איך זה ייקרא": a sentence's words paired with the IPA the voices get, for showing word by word. */
object Readings {
    private val PUNCT = Regex("[.,!?…:;\"()\\[\\]״]+")
    val SENTENCES = Regex("(?<=[.!?…])\\s+|\\n+")

    /** (word as shown, its IPA) for each word of [sentence], or null when the words and the IPA do not line up. */
    fun pair(sentence: String, ipa: String): List<Pair<String, String>>? {
        // A hyphen between two words is two words for the G2P (קרטר-ויקרס); a one-letter prefix (ב-תיקו) stays one.
        val words = HebrewNumbers.normalize(sentence).split(Regex("\\s+"))
            .flatMap { w -> if (Regex("[\\u05D0-\\u05EA]{2,}-[\\u05D0-\\u05EA]{2,}").containsMatchIn(w)) w.split('-') else listOf(w) }
            .map { it.replace(PUNCT, "") }.filter { w -> w.any { it.isLetterOrDigit() } }
        val ipas = ipa.split(Regex("\\s+")).map { it.replace(PUNCT, "") }.filter { w -> w.any { it.isLetter() } }
        if (words.isEmpty()) return null
        if (words.size == ipas.size) return words.zip(ipas)
        // A lexicon name may be several words of IPA (פרמיירליג -> pʁemjˈeʁ lˈiɡ): give each word as many as its entry has.
        val counts = words.map { w -> Lexicon.apply(w).let { if (it == w) 1 else it.split(' ').count { t -> t.isNotBlank() }.coerceAtLeast(1) } }
        if (counts.sum() != ipas.size) return null
        var k = 0
        return words.mapIndexed { i, w -> (w to ipas.subList(k, k + counts[i]).joinToString(" ")).also { k += counts[i] } }
    }

    fun clean(ipa: String) = ipa.replace(PUNCT, "").trim()
}
