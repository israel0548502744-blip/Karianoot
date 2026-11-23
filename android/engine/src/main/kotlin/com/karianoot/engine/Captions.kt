package com.karianoot.engine

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Word-by-word captions for a narration, from where each spoken piece sits in the recording.
 * Within a piece the words share its *voiced* time in proportion to how long they are to say (digits counted as the
 * words they are read as), so pauses fall between words instead of stretching them. Words then go into short
 * caption cards (one or two lines, a sentence never split across two cards when it fits).
 */
object Captions {
    data class Word(val text: String, val start: Float, val end: Float)
    data class Card(val words: List<Word>) {
        val start get() = words.first().start
        val end get() = words.last().end
        val text get() = words.joinToString(" ") { it.text }
    }

    /** A piece of the recording: its text and where it is, in samples. */
    data class Span(val text: String, val start: Int, val end: Int)

    private val NIQQUD = Regex("[\\u0591-\\u05C7]")

    /** Text as shown on screen: no niqqud, no stray IPA from pronunciation fixes, no markers. */
    fun display(text: String): String =
        text.replace(NIQQUD, "").replace(Script.MARKER, " ").replace(Regex("[ˈʔʁʃχɡ]"), "").replace(Regex("\\s+"), " ").trim()

    /** How long a word takes to say, roughly: letters of its spoken form, at least 1. */
    fun weight(word: String): Int {
        val spoken = if (word.any { it.isDigit() }) runCatching { HebrewNumbers.normalize(word) }.getOrDefault(word) else word
        return max(1, spoken.count { it.isLetter() }) + 1
    }

    fun words(spans: List<Span>, audio: FloatArray, rate: Int): List<Word> {
        val out = ArrayList<Word>()
        for (s in spans) {
            val ws = display(s.text).split(' ').filter { it.isNotBlank() }
            if (ws.isEmpty()) continue
            val a = s.start.coerceIn(0, audio.size); val b = s.end.coerceIn(a, audio.size)
            val hop = max(1, rate / 100)
            val frames = max(1, (b - a) / hop)
            // Loud 10 ms frames are speech.
            val e = FloatArray(frames) { f ->
                var sum = 0.0; val x0 = a + f * hop; val x1 = min(b, x0 + hop)
                for (k in x0 until x1) sum += audio[k] * audio[k]
                sqrt(sum / max(1, x1 - x0)).toFloat()
            }
            val peak = e.maxOrNull() ?: 0f
            val loud = BooleanArray(frames) { peak > 0f && 20 * log10(e[it] / peak + 1e-9f) > -35f }
            if (loud.none { it }) loud.fill(true)
            val first = loud.indexOfFirst { it }; val last = loud.indexOfLast { it }
            // Pauses inside the speech (quiet runs of 120 ms or more) split it into islands.
            val pauses = ArrayList<IntArray>()
            var f = first
            while (f <= last) {
                if (!loud[f]) { val q = f; while (f <= last && !loud[f]) f++; if (f - q >= 12) pauses.add(intArrayOf(q, f)) } else f++
            }
            val cum = IntArray(frames + 1); for (k in 0 until frames) cum[k + 1] = cum[k] + if (loud[k]) 1 else 0
            val voiced = cum[frames].toDouble()
            val wt = ws.map { weight(it) }
            val cw = IntArray(ws.size + 1); for (k in ws.indices) cw[k + 1] = cw[k] + wt[k]
            val wsum = cw[ws.size].toDouble()
            // Each pause takes the word boundary nearest to where it falls (a comma or full stop there counts extra).
            val cuts = ArrayList<Pair<Int, IntArray>>()       // (boundary: words before it, the pause)
            var prev = 0
            pauses.forEachIndexed { j, p ->
                val expected = cum[p[0]] / voiced * wsum
                val left = pauses.size - j - 1
                var best = -1; var bestCost = Double.MAX_VALUE
                for (bd in prev + 1..ws.size - 1 - left) {
                    val punct = ws[bd - 1].last() in ".,!?…:;"
                    val cost = kotlin.math.abs(cw[bd] - expected) / wsum - (if (punct) 0.15 else 0.0)
                    if (cost < bestCost) { bestCost = cost; best = bd }
                }
                if (best > 0) { cuts.add(best to p); prev = best }
            }
            // Islands: [frame range, words range]; the words of each share its voiced time by weight.
            val bounds = listOf(0) + cuts.map { it.first } + listOf(ws.size)
            val frameEdges = listOf(first) + cuts.flatMap { listOf(it.second[0], it.second[1]) } + listOf(last + 1)
            for (k in 0 until bounds.size - 1) {
                val f0 = frameEdges[2 * k]; val f1 = frameEdges[2 * k + 1]
                val w0 = bounds[k]; val w1 = bounds[k + 1]
                val v0 = cum[f0]; val vLen = max(1, cum[f1] - v0)
                val wLen = (cw[w1] - cw[w0]).toDouble()
                // The frame (in f0 until f1) at which [v] voiced frames of the island have passed.
                fun frameAt(v: Double, start: Boolean = true): Int {
                    val target = v0 + v.coerceIn(0.0, vLen.toDouble())
                    var lo = f0; var hi = f1 - 1
                    while (lo < hi) { val m = (lo + hi) / 2; if (cum[m + 1] <= target) lo = m + 1 else hi = m }
                    var g = lo
                    if (start) while (g < f1 - 1 && !loud[g]) g++
                    return g
                }
                // Each word starts where its share of the voiced time begins and lasts until the next one starts.
                val starts = IntArray(w1 - w0) { i -> frameAt((cw[w0 + i] - cw[w0]) / wLen * vLen, true) }
                for (i in starts.indices) {
                    val s0 = starts[i]
                    val s1 = max(s0 + 1, if (i + 1 < starts.size) starts[i + 1] else f1)
                    out.add(Word(ws[w0 + i], (a + s0 * hop).toFloat() / rate, (a + min(f1, s1) * hop).toFloat() / rate))
                }
            }
        }
        return out
    }

    /** Words -> cards of at most [maxChars] characters; a new card after a sentence end or a pause over 0.35 s. */
    fun cards(words: List<Word>, maxChars: Int = 22): List<Card> {
        val out = ArrayList<Card>()
        var cur = ArrayList<Word>()
        fun flush() { if (cur.isNotEmpty()) { out.add(Card(cur)); cur = ArrayList() } }
        for (w in words) {
            val len = cur.sumOf { it.text.length + 1 } + w.text.length
            if (cur.isNotEmpty() && (len > maxChars || w.start - cur.last().end > 0.35f)) flush()
            cur.add(w)
            if (w.text.last() in ".!?…:") flush()
        }
        flush()
        return out
    }

    private fun stamp(t: Float): String {
        val ms = (t * 1000).toLong().coerceAtLeast(0)
        return "%02d:%02d:%02d,%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
    }

    /** SubRip (.srt): what TikTok / CapCut / Premiere import as captions. */
    fun srt(cards: List<Card>): String = buildString {
        cards.forEachIndexed { i, c ->
            val end = cards.getOrNull(i + 1)?.let { min(it.start, c.end + 0.6f) } ?: (c.end + 0.6f)
            append(i + 1).append('\n').append(stamp(c.start)).append(" --> ").append(stamp(max(end, c.start + 0.3f))).append('\n')
            append('‫').append(c.text).append('‬').append("\n\n")
        }
    }
}
