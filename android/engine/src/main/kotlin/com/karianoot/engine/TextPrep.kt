package com.karianoot.engine

/**
 * Football-tips text cleanup before the pronunciation engine: things the G2P cannot read or reads wrong.
 *  - "1X2" (the bet) -> "אחת איקס שתיים"; a handicap "-1" keeps its minus; a score "2:1" is a score, not a time.
 *  - Hebrew abbreviations of places: ת"א, ב"ש, פ"ת, ר"ג, ק"ש.
 *  - Latin acronyms (PSG, VAR, BTTS) -> Hebrew letter names with niqqud; a few are said as words (VAR, UEFA, FIFA).
 * Runs before the number reader, so it only reshapes what the number reader would get wrong.
 */
object TextPrep {
    private val WORDS = mapOf(
        "VAR" to "וָאר", "UEFA" to "אוּפָ\"א", "FIFA" to "פִיפָ\"א", "NBA" to "אֶן בִּי אֵיי", "OK" to "אוֹקֵיי",
    )
    private val LETTERS = mapOf(
        'A' to "אֵיי", 'B' to "בִּי", 'C' to "סִי", 'D' to "דִּי", 'E' to "אִי", 'F' to "אֶף", 'G' to "גִ'י", 'H' to "אֵייץ'",
        'I' to "אַיי", 'J' to "גֵ'יי", 'K' to "קֵיי", 'L' to "אֶל", 'M' to "אֶם", 'N' to "אֶן", 'O' to "אוֹ", 'P' to "פִּי",
        'Q' to "קְיוּ", 'R' to "אַר", 'S' to "אֶס", 'T' to "טִי", 'U' to "יוּ", 'V' to "וִי", 'W' to "דַּאבְּלְיוּ", 'X' to "אֶקְס",
        'Y' to "וַאי", 'Z' to "זֶד",
    )
    private val ABBREV = mapOf(
        "ת\"א" to "תל אביב", "ב\"ש" to "באר שבע", "פ\"ת" to "פתח תקווה", "ר\"ג" to "רמת גן", "ק\"ש" to "קריית שמונה",
    )
    private const val HEB = "\\u05D0-\\u05EA"

    fun apply(text: String): String {
        var t = text.replace('״', '"').replace('׳', '\'')
        // The bet "1X2" (also 1x2), and its parts "X2" / "1X" (double chance).
        t = Regex("(?<![\\w])1\\s*[xX×]\\s*2(?![\\w])").replace(t, "אחת איקס שתיים")
        t = Regex("(?<![\\w])1[xX](?![\\w])").replace(t, "אחת איקס")
        t = Regex("(?<![\\w])[xX]2(?![\\w])").replace(t, "איקס שתיים")
        // Handicap / negative: "-1" after a space or the start keeps its minus.
        t = Regex("(?<=^|[\\s(])[-−](?=\\d)").replace(t, "מינוס ")
        // A score with one-digit minutes ("2:1", "ה-1:0") is a score, not a time ("21:45" stays a time).
        t = Regex("(?<!\\d)(\\d{1,2}):(\\d)(?!\\d)").replace(t) { "${it.groupValues[1]}-${it.groupValues[2]}" }
        for ((k, v) in ABBREV) t = Regex("(?<![$HEB])([ובלמה]?)${Regex.escape(k)}(?![$HEB])").replace(t) { it.groupValues[1] + v }
        // Latin acronyms: 2-6 capitals, optionally after a Hebrew prefix and hyphen ("ה-VAR").
        t = Regex("(?<![A-Za-z])([A-Z]{2,6})(?![A-Za-z])").replace(t) { m ->
            val w = m.groupValues[1]
            WORDS[w] ?: w.map { LETTERS.getValue(it) }.joinToString(" ")
        }
        return t
    }
}
