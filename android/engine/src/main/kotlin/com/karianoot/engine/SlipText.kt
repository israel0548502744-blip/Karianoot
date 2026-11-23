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
        if (picks.size > 1 && all.size == picks.size) sb.append("\nביחד זה טופס ביחס כולל של ${ScriptWriter.total(all)}.")
        sb.append("\nבהצלחה לכולם, ותעקבו כדי לא לפספס את הטופס הבא!")
        return sb.toString()
    }

    /** A word Tesseract found: text, box (pixels), confidence, and which line it is on (Hebrew pass). */
    data class OcrWord(val text: String, val x: Int, val y: Int, val w: Int, val h: Int, val conf: Float, val line: Int)

    private val HOCR_LINE = Regex("<span class=['\"]ocr_line['\"][^>]*>(.*?)</span>\\s*</span>|<span class=['\"]ocr_line['\"][^>]*>", RegexOption.DOT_MATCHES_ALL)
    private val HOCR_WORD = Regex("<span class=['\"]ocrx_word['\"][^>]*title=['\"]bbox (\\d+) (\\d+) (\\d+) (\\d+); x_wconf (\\d+)['\"][^>]*>(.*?)</span>", RegexOption.DOT_MATCHES_ALL)

    /** Words of a Tesseract hOCR page, numbered by line. */
    fun hocrWords(hocr: String): List<OcrWord> {
        val out = ArrayList<OcrWord>()
        // Split on line starts; every word after a line start belongs to that line.
        val parts = hocr.split(Regex("(?=<span class=['\"]ocr_line['\"])"))
        parts.forEachIndexed { li, part ->
            for (m in HOCR_WORD.findAll(part)) {
                val (x0, y0, x1, y1, c) = m.destructured
                val text = m.groupValues[6].replace(Regex("<[^>]+>"), "").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim()
                if (text.isNotEmpty()) out.add(OcrWord(text, x0.toInt(), y0.toInt(), x1.toInt() - x0.toInt(), y1.toInt() - y0.toInt(), c.toFloat(), li))
            }
        }
        return out
    }

    private val FINAL_INSIDE = Regex("[ךםןףץ](?=[\\u05D0-\\u05EA])")

    /**
     * A Hebrew-pass word that is really something else: a number the Hebrew model turned into letters (a final letter
     * inside a word, Latin/digit noise) or a lone letter (a "1" or "X" pick). It is read again, digits only, from its box.
     */
    fun needsDigits(t: String) = FINAL_INSIDE.containsMatchIn(t) || (t.length == 1 && t[0].isLetter()) ||
        (Regex("[A-Za-z0-9]").containsMatchIn(t) && !Regex("[\\u05D0-\\u05EA'׳\"״\\-]+").matches(t))

    /**
     * The words of the Hebrew pass, with every [needsDigits] word replaced by what [reread] reads in its box (digits only;
     * null or not digits = dropped), as lines top to bottom, right to left.
     */
    fun text(words: List<OcrWord>, reread: (OcrWord) -> String?): String {
        val fixed = words.mapNotNull { w ->
            if (!needsDigits(w.text)) w else reread(w)?.trim()?.takeIf { Regex("[0-9.,X]+").matches(it) }?.let { w.copy(text = it) }
        }
        return fixed.groupBy { it.line }.values.sortedBy { ws -> ws.minOf { it.y } }
            .joinToString("\n") { ws -> ws.sortedByDescending { it.x }.joinToString(" ") { it.text } }
    }
}
