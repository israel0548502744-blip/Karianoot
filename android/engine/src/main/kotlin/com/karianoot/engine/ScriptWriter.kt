package com.karianoot.engine

import java.util.Locale
import kotlin.random.Random

/**
 * "כותב תסריטים": matches (teams, pick, odds, an optional reason) -> a tips-video script in natural spoken Hebrew.
 * Phrase banks, not a language model: the small models a phone can run wrote broken Hebrew (llm-probe.yml, Nov 23),
 * while these lines are written by hand. Every [seed] gives other wording ("עוד גרסה").
 */
object ScriptWriter {
    data class Match(val match: String, val pick: String, val odds: Float?, val reason: String = "")
    enum class Style { HYPE, CALM, DRAMA }

    private val OPEN = mapOf(
        Style.HYPE to listOf("עצרו הכל! הטופס של היום כאן, ואתם לא רוצים לפספס אותו.", "ערב טוב לכל האוהדים! יש לי בשבילכם טופס מטורף.",
            "מוכנים? כי הערב אנחנו הולכים על הכל!", "יום המזל שלכם מתחיל עכשיו! תקשיבו טוב."),
        Style.CALM to listOf("ערב טוב. הנה הטופס שלי להיום, עם נימוק לכל משחק.", "שלום לכולם. בואו נעבור על המשחקים של הערב, אחד אחד.",
            "ערב טוב. בדקתי את המשחקים של היום, ואלה הבחירות שלי."),
        Style.DRAMA to listOf("יש ערבים שמשנים הכל. הערב הוא אחד מהם.", "כולם מדברים על המשחקים של הערב. אבל רק מעטים יודעים על מה לשים.",
            "תנו לי דקה אחת. אני אחסוך לכם ערב שלם של התלבטויות."),
    )
    private val NTH = listOf("הראשון", "השני", "השלישי", "הרביעי", "החמישי", "השישי", "השביעי", "השמיני")
    private val INTRO = listOf("המשחק %s: %s.", "נתחיל במשחק %s: %s.", "המשחק %s שלנו: %s.")
    private val NEXT = listOf("ועכשיו למשחק %s: %s.", "ממשיכים למשחק %s: %s.", "המשחק %s: %s.")
    private val LAST = listOf("ולסיום, המשחק %s: %s.", "והמשחק האחרון: %s.")
    private val PICK = listOf("אני הולך על %s", "ההימור שלי: %s", "פה אני ממליץ על %s", "אני שם על %s")
    private val ODDS = listOf(", ביחס של %s.", ". היחס? %s.", ", והיחס עומד על %s.")
    private val WHY = listOf("למה? %s.", "הסיבה פשוטה: %s.", "ולא סתם: %s.")
    private val BANKER = listOf("וזה הבנקר של הטופס.", "פה אני לא רואה הפתעות.", "זה המשחק הכי בטוח של הערב.")
    private val BOLD = listOf("זה ההימור הנועז של הערב.", "פה יש סיכון, אבל גם פוטנציאל גדול.")
    private val TOTAL = listOf("ביחד זה טופס ביחס כולל של %s.", "ביחד? יחס כולל של %s.", "הכל ביחד יוצא ביחס כולל של %s.")
    private val CLOSE = mapOf(
        Style.HYPE to listOf("בהצלחה לכולם! תעקבו כדי לא לפספס את הטופס של מחר!", "אם אהבתם, תנו לייק ותעקבו. מחר יש עוד!",
            "כתבו לי בתגובות על מה אתם הולכים. בהצלחה!"),
        Style.CALM to listOf("תזכרו לשים רק מה שאתם מוכנים להפסיד. בהצלחה, ונתראה מחר.", "זהו להיום. בהצלחה, ונתראה בטופס הבא."),
        Style.DRAMA to listOf("עכשיו זה בידיים שלכם. בהצלחה.", "הכדור עגול, והערב הוא שלנו. נתראה אחרי המשחקים."),
    )

    private fun num(f: Float) = "%.2f".format(Locale.US, f)
    /** The product of the odds, exactly as a bookmaker shows it (2.10 x 1.75 x 3.40 = 12.495 -> 12.50, not 12.49). */
    fun total(odds: List<Float>): String = odds.fold(java.math.BigDecimal.ONE) { a, o -> a.multiply(java.math.BigDecimal(num(o))) }
        .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()

    private fun <T> List<T>.pick(r: Random) = this[r.nextInt(size)]

    fun write(matches: List<Match>, style: Style = Style.HYPE, seed: Int = 0): String {
        val ms = matches.filter { it.match.isNotBlank() }
        if (ms.isEmpty()) return ""
        val r = Random(seed)
        val out = ArrayList<String>()
        out.add(OPEN.getValue(style).pick(r))
        val withOdds = ms.mapNotNull { it.odds }
        // The lowest odds of three or more is "the banker"; the highest, when it is long, "the bold one".
        val banker = if (ms.size >= 3 && withOdds.size == ms.size) ms.minByOrNull { it.odds!! } else null
        val bold = if (ms.size >= 2) ms.filter { (it.odds ?: 0f) >= 3f }.maxByOrNull { it.odds!! } else null
        ms.forEachIndexed { i, m ->
            val n = NTH.getOrElse(i) { "הבא" }
            val head = when {
                ms.size == 1 -> "המשחק של הערב: ${m.match}."
                i == 0 -> INTRO.pick(r).format(n, m.match)
                i == ms.size - 1 && ms.size >= 3 -> LAST.pick(r).let { if (it.count { c -> c == '%' } == 2) it.format(n, m.match) else it.format(m.match) }
                else -> NEXT.pick(r).format(n, m.match)
            }
            val sb = StringBuilder(head)
            if (m.pick.isNotBlank()) {
                sb.append(' ').append(PICK.pick(r).format(m.pick))
                sb.append(m.odds?.let { ODDS.pick(r).format(num(it)) } ?: ".")
            } else m.odds?.let { sb.append(" היחס ${num(it)}.") }
            if (m.reason.isNotBlank()) sb.append(' ').append(WHY.pick(r).format(m.reason.trim().trimEnd('.', '!')))
            if (m === banker) sb.append(' ').append(BANKER.pick(r))
            else if (m === bold) sb.append(' ').append(BOLD.pick(r))
            if (style == Style.HYPE && i == 0) sb.append(" זה הולך להיות ערב גדול!")
            out.add(sb.toString())
        }
        if (ms.size > 1 && withOdds.size == ms.size) out.add(TOTAL.pick(r).format(total(withOdds)))
        out.add(CLOSE.getValue(style).pick(r))
        return out.joinToString("\n")
    }
}
