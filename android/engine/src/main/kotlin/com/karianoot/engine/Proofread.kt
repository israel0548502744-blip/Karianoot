package com.karianoot.engine

import java.text.Normalizer

/**
 * The proofreader's judgement (as tools/proof/run.py): what was meant vs what the recognizer heard, word by word.
 * A word counts as missed when, after aligning the two word lists, it sits in a replaced or deleted stretch whose letters
 * are less than 60% alike. Stretches where the recognizer wrote digits are skipped (it writes "3.40" for "שלוש ארבעים").
 */
object Proofread {
    data class Miss(val ref: String, val heard: String, val similarity: Float)

    private val FINALS = mapOf('ך' to 'כ', 'ם' to 'מ', 'ן' to 'נ', 'ף' to 'פ', 'ץ' to 'צ')

    fun words(s: String): List<String> {
        val nfd = Normalizer.normalize(s, Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        return nfd.replace('-', ' ').replace(Regex("[^\\u05D0-\\u05EA0-9 ]+"), " ").split(' ').filter { it.isNotBlank() }
            .map { w -> w.map { FINALS[it] ?: it }.joinToString("") }
    }

    /** 2 x (common letters, in order) / (total letters): 1 = the same, 0 = nothing alike. */
    fun similarity(a: String, b: String): Float {
        if (a.isEmpty() && b.isEmpty()) return 1f
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 1..a.length) for (j in 1..b.length) d[i][j] = if (a[i - 1] == b[j - 1]) d[i - 1][j - 1] + 1 else maxOf(d[i - 1][j], d[i][j - 1])
        return 2f * d[a.length][b.length] / (a.length + b.length)
    }

    /** Alignment of two word lists: runs of (refFrom, refTo, hypFrom, hypTo, equal). */
    private fun align(r: List<String>, h: List<String>): List<IntArray> {
        val n = r.size; val m = h.size
        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) d[i][j] = if (r[i] == h[j]) d[i + 1][j + 1] + 1 else maxOf(d[i + 1][j], d[i][j + 1])
        val out = ArrayList<IntArray>()
        var i = 0; var j = 0; var si = 0; var sj = 0
        fun flush(ei: Int, ej: Int) { if (ei > si || ej > sj) out.add(intArrayOf(si, ei, sj, ej, 0)) }
        while (i < n && j < m) {
            if (r[i] == h[j]) { flush(i, j); out.add(intArrayOf(i, i + 1, j, j + 1, 1)); i++; j++; si = i; sj = j }
            else if (d[i + 1][j] >= d[i][j + 1]) i++ else j++
        }
        flush(n, m); si = n; sj = m
        return out
    }

    fun missed(ref: String, hyp: String): List<Miss> {
        val r = words(ref); val h = words(hyp)
        val bad = ArrayList<Miss>()
        for (run in align(r, h)) {
            if (run[4] == 1) continue
            val rs = r.subList(run[0], run[1]); val hs = h.subList(run[2], run[3])
            if (rs.isEmpty() || hs.any { w -> w.any { it.isDigit() } }) continue
            val sim = similarity(rs.joinToString(""), hs.joinToString(""))
            if (sim < 0.6f) bad.add(Miss(rs.joinToString(" "), hs.joinToString(" "), sim))
        }
        return bad
    }
}
