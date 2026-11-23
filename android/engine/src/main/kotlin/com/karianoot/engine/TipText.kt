package com.karianoot.engine

import java.util.Locale

/** The words of the tips journal: tips found in a script, and the weekly summary script. */
object TipText {
    data class Pick(val match: String, val pick: String, val odds: Float?)

    private const val W = "[\\u05D0-\\u05EA'\"׳״\\-]+"
    private val VS = Regex("($W(?:\\s$W){0,2})\\s+(?:נגד|מול)\\s+($W(?:\\s$W){0,2})")
    private val ODDS = Regex("(?:יחס|ביחס)\\s*(?:של\\s*)?(\\d+(?:\\.\\d+)?)")
    private val PICK = Regex("(?:הולך על|ממליץ על|שמים על|הימור על)\\s+([^,.!?\\n]{2,30}?)(?=\\s*[,.!?]|\\s+ביחס|\\s+יחס|$)")
    /** Words that come before a team name in a script but are not part of it. */
    private val NOT_TEAM = setOf("היום", "הערב", "המשחק", "הראשון", "השני", "השלישי", "האחרון", "בין", "את", "זה", "של", "גם", "ועכשיו", "אז", "פה", "יש")

    private fun team(words: List<String>) = words.dropWhile { it.removePrefix("ו") in NOT_TEAM || it in NOT_TEAM }.joinToString(" ")

    /** Tips in a script: "X נגד Y" (or מול), with the pick ("הולך על …") and odds ("ביחס 2.10") in that sentence or the next. */
    fun parse(script: String): List<Pick> {
        val sentences = script.split(Regex("(?<=[.!?])\\s+|\\n+")).filter { it.isNotBlank() }
        val out = ArrayList<Pick>()
        for ((i, s) in sentences.withIndex()) {
            val m = VS.find(s) ?: continue
            val a = team(m.groupValues[1].split(' '))
            if (a.isEmpty()) continue
            val b = m.groupValues[2].split(' ').filter { it !in NOT_TEAM }.joinToString(" ")
            val after = s.substring(m.range.last + 1) + " " + sentences.getOrElse(i + 1) { "" }
            val odds = ODDS.find(after)?.groupValues?.get(1)?.toFloatOrNull()
            val pick = PICK.find(after)?.groupValues?.get(1)?.trim() ?: ""
            out.add(Pick("$a נגד $b", pick, odds))
        }
        return out.distinctBy { it.match }
    }

    private fun num(f: Float) = "%.2f".format(Locale.US, f)

    /** "סיכום השבוע": [decided] tips closed, [won] of them won, [profit] units at one unit a tip, the best [wins]. */
    fun summary(decided: Int, won: Int, profit: Float, wins: List<Pick>): String {
        if (decided == 0) return "סיכום השבוע! השבוע עוד לא נסגרו טיפים. נתראה בטופס הבא!"
        val percent = Math.round(won * 100f / decided)
        val sb = StringBuilder(if (percent >= 60) "איזה שבוע! " else "סיכום השבוע. ")
        sb.append("מתוך $decided טיפים, פגענו ב-$won. זה $percent אחוז הצלחה.")
        val best = wins.sortedByDescending { it.odds ?: 0f }.take(3)
        if (best.isNotEmpty()) sb.append(" הפגיעות הגדולות: ").append(best.joinToString(", ") { w ->
            listOf(w.match, w.pick).filter { it.isNotBlank() }.joinToString(", ") + (w.odds?.let { " ביחס ${num(it)}" } ?: "")
        }).append(".")
        if (profit > 0.05f) sb.append(" מי ששם יחידה אחת על כל טיפ, הרוויח ${num(profit)} יחידות.")
        sb.append(if (percent >= 50) " ובשבוע הבא? ממשיכים באותו קצב. תעקבו!" else " שבוע קשה, אבל ממשיכים. בשבוע הבא חוזרים חזק!")
        return sb.toString()
    }
}
