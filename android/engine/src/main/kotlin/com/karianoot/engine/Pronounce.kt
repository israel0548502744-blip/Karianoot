package com.karianoot.engine

/**
 * The user's pronunciation fixes: "say this word like that". Applied to the text before the
 * pronunciation engine reads it. A word also matches with the one-letter Hebrew prefixes in front
 * of it (ו, ה, ב, ל, מ, ש, כ), which are kept: with היחס → "הייאחס", "והיחס" becomes "והייאחס".
 */
object Pronounce {
    data class Fix(val word: String, val sayAs: String)

    private const val HEB = "\\u05D0-\\u05EA\\u0591-\\u05C7"

    fun apply(text: String, fixes: List<Fix>): String {
        var out = text
        for (f in fixes.sortedByDescending { it.word.length }) {
            val w = HebrewG2P.stripNiqqud(f.word.trim())
            if (w.isEmpty() || f.sayAs.isBlank()) continue
            val re = Regex("(?<![$HEB])([ובהלמשכ]{0,3})${Regex.escape(w)}(?![$HEB])")
            out = re.replace(out) { m -> m.groupValues[1] + f.sayAs.trim() }
        }
        return out
    }
}
