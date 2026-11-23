package com.karianoot.engine

/** "איך זה ייקרא": a sentence's words paired with the IPA the voices get, for showing word by word. */
object Readings {
    private val PUNCT = Regex("[.,!?…:;\"()\\[\\]״]+")
    val SENTENCES = Regex("(?<=[.!?…])\\s+|\\n+")

    /** (word as shown, its IPA) for each word of [sentence], or null when the words and the IPA do not line up. */
    fun pair(sentence: String, ipa: String): List<Pair<String, String>>? {
        val words = HebrewNumbers.normalize(sentence).split(Regex("\\s+")).map { it.replace(PUNCT, "") }.filter { w -> w.any { it.isLetterOrDigit() } }
        val ipas = ipa.split(Regex("\\s+")).map { it.replace(PUNCT, "") }.filter { w -> w.any { it.isLetter() } }
        return if (words.size == ipas.size && words.isNotEmpty()) words.zip(ipas) else null
    }

    fun clean(ipa: String) = ipa.replace(PUNCT, "").trim()
}
