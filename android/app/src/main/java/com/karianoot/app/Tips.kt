package com.karianoot.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * "יומן הטיפים": every tip the user gave (match, pick, odds) and whether it came in, for the success rate and for a
 * ready-made weekly summary script ("השבוע פגענו ב-7 מתוך 10!"). Kept next to the recordings (and in the backup).
 */
class Tips(ctx: Context) {
    enum class Status { PENDING, WON, LOST, VOID }
    data class Tip(val id: Long, val date: String, val match: String, val pick: String, val odds: Float?, val status: Status = Status.PENDING) {
        val day: LocalDate get() = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())
    }
    data class Stats(val total: Int, val won: Int, val lost: Int, val profit: Float) {
        val decided get() = won + lost
        val percent get() = if (decided == 0) 0 else Math.round(won * 100f / decided)
    }

    private val file = File(ctx.getExternalFilesDir(null), "tips.json")
    private val _list = MutableStateFlow(load())
    val list: StateFlow<List<Tip>> = _list

    private fun load(): List<Tip> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            Tip(o.getLong("id"), o.getString("date"), o.getString("match"), o.optString("pick"),
                o.optDouble("odds", Double.NaN).takeIf { !it.isNaN() }?.toFloat(), Status.valueOf(o.optString("status", "PENDING")))
        }
    }.getOrDefault(emptyList())

    private fun save(l: List<Tip>) {
        val a = JSONArray()
        for (t in l.sortedByDescending { it.date + "%015d".format(it.id) }) a.put(JSONObject().put("id", t.id).put("date", t.date).put("match", t.match)
            .put("pick", t.pick).put("status", t.status.name).also { o -> t.odds?.let { o.put("odds", it.toDouble()) } })
        file.writeText(a.toString())
        _list.value = l.sortedByDescending { it.date + "%015d".format(it.id) }
    }

    fun add(match: String, pick: String, odds: Float?, date: LocalDate = LocalDate.now()) {
        if (match.isBlank()) return
        save(_list.value + Tip(System.currentTimeMillis() + _list.value.size, date.toString(), match.trim(), pick.trim(), odds))
    }

    fun set(t: Tip, s: Status) = save(_list.value.map { if (it.id == t.id) it.copy(status = s) else it })
    fun remove(t: Tip) = save(_list.value.filter { it.id != t.id })

    companion object {
        /** This week: Sunday to Saturday (the Israeli week). */
        fun weekStart(today: LocalDate = LocalDate.now()): LocalDate = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))

        /** Profit at one unit per tip: a win pays odds - 1, a loss costs 1. */
        fun stats(l: List<Tip>): Stats {
            val won = l.count { it.status == Status.WON }; val lost = l.count { it.status == Status.LOST }
            val profit = l.sumOf { t -> when (t.status) { Status.WON -> ((t.odds ?: 1f) - 1f).toDouble(); Status.LOST -> -1.0; else -> 0.0 } }.toFloat()
            return Stats(l.size, won, lost, profit)
        }

        /** A narration script summing up [l] (one week), ready for the studio. */
        fun summary(l: List<Tip>): String {
            val s = stats(l)
            return com.karianoot.engine.TipText.summary(s.decided, s.won, s.profit,
                l.filter { it.status == Status.WON }.map { com.karianoot.engine.TipText.Pick(it.match, it.pick, it.odds) })
        }
    }
}
