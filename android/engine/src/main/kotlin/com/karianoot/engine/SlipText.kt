package com.karianoot.engine

import java.util.Locale

/**
 * A betting slip read from a screenshot (OCR text) -> the tips on it, and a script built from them.
 * A match is a line "קבוצה - קבוצה" (or נגד / מול / vs); its pick (1 / X / 2, 1X, X2, 12, or words like "מעל 2.5")
 * and its odds (a number like 2.10) are on that line or the next few.
 */
object SlipText {
    private const val TEAM = "[\\u05D0-\\u05EA'׳\"״.]+(?:\\s[\\u05D0-\\u05EA'׳\"״.]+){0,3}"
    private val MATCH = Regex("($TEAM)\\s*(?:-|–|—|נגד|מול|vs\\.?|VS)\\s*($TEAM)")
    private val ODDS = Regex("(?<![\\d.])(\\d{1,2}[.,]\\d{2})(?![\\d.])")
    private val SIMPLE = Regex("(?<![\\w\\u05D0-\\u05EA])(1X|X2|12|1|X|2)(?![\\w\\u05D0-\\u05EA.,])")
    private val WORDS = listOf(
        Regex("(מעל|מתחת ל?|מתחת)\\s*(\\d+[.,]5)(\\s*שערים)?"),
        Regex("שתי הקבוצות (?:יבקיעו|מבקיעות)(?: - ?(?:כן|לא))?"),
        Regex("(?:ניצחון|נצחון)\\s+$TEAM"),
        Regex("תיקו"),
    )

    /** A match as said in a script: teams and pick. */
    fun parse(ocr: String): List<TipText.Pick> {
        val lines = ocr.lines().map { it.replace('|', ' ').trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<TipText.Pick>()
        var i = 0
        while (i < lines.size) {
            val m = MATCH.find(lines[i])
            if (m == null) { i++; continue }
            val a = m.groupValues[1].trim(); val b = m.groupValues[2].trim()
            // Look at the rest of this line and the next three, up to the next match.
            val around = StringBuilder(lines[i].substring(m.range.last + 1))
            var j = i + 1
            while (j < lines.size && j <= i + 3 && MATCH.find(lines[j]) == null) { around.append('\n').append(lines[j]); j++ }
            val text = around.toString()
            val odds = ODDS.find(text)?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull()
            val noOdds = ODDS.replace(text, " ")
            val pick = WORDS.firstNotNullOfOrNull { it.find(noOdds)?.value?.trim() }
                ?: SIMPLE.find(noOdds)?.groupValues?.get(1)?.let { s ->
                    when (s) { "1" -> "ניצחון $a"; "2" -> "ניצחון $b"; "X" -> "תיקו"; "1X" -> "$a או תיקו"; "X2" -> "$b או תיקו"; else -> "לא תיקו" }
                } ?: ""
            out.add(TipText.Pick("$a נגד $b", pick, odds))
            i = j
        }
        return out.distinctBy { it.match }
    }

    private fun num(f: Float) = "%.2f".format(Locale.US, f)

    /** A tips-video script from the slip: an opener, one line per match, the total odds when there are several. */
    fun script(picks: List<TipText.Pick>): String {
        if (picks.isEmpty()) return ""
        val order = listOf("הראשון", "השני", "השלישי", "הרביעי", "החמישי", "השישי", "השביעי", "השמיני")
        val sb = StringBuilder(if (picks.size == 1) "ערב טוב לכל האוהדים! יש לי בשבילכם משחק אחד, ואני בטוח בו." else
            "ערב טוב לכל האוהדים! הטופס של היום מוכן, ויש בו ${picks.size} משחקים.")
        picks.forEachIndexed { i, p ->
            val nth = if (picks.size == 1) "המשחק" else "המשחק ${order.getOrElse(i) { "הבא" }}"
            sb.append("\n$nth: ${p.match}.")
            if (p.pick.isNotBlank()) sb.append(" אני הולך על ${p.pick}" + (p.odds?.let { ", ביחס ${num(it)}." } ?: "."))
            else p.odds?.let { sb.append(" היחס ${num(it)}.") }
        }
        val all = picks.mapNotNull { it.odds }
        if (picks.size > 1 && all.size == picks.size) sb.append("\nביחד זה טופס ביחס כולל של ${num(all.fold(1f) { x, y -> x * y })}.")
        sb.append("\nבהצלחה לכולם, ותעקבו כדי לא לפספס את הטופס הבא!")
        return sb.toString()
    }
}
