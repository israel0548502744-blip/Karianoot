package com.karianoot.engine

/**
 * Markers the user can put in a script, in braces: {הפסקה} (a 1 s pause), {הפסקה 2} or {הפסקה 0.5} (that many seconds),
 * and the sound effects of [Sfx.NAMES] ({שריקה}, {קהל}, ...). Everything else is speech.
 */
object Script {
    sealed interface Seg
    data class Speech(val text: String) : Seg
    data class Pause(val seconds: Float) : Seg
    data class Effect(val name: String) : Seg

    val MARKER = Regex("\\{\\s*([^{}]{1,24}?)\\s*\\}")
    private val PAUSE = Regex("הפסקה(?:\\s+(\\d+(?:[.,]\\d+)?))?(?:\\s*שנ(?:יות|׳|')?)?")

    /** What a marker's inside means, or null when it is not one we know. */
    fun marker(inside: String): Seg? {
        val s = inside.trim()
        PAUSE.matchEntire(s)?.let { m ->
            val sec = m.groupValues[1].replace(',', '.').toFloatOrNull() ?: 1f
            return Pause(sec.coerceIn(0.1f, 10f))
        }
        return if (s in Sfx.NAMES) Effect(s) else null
    }

    /** The markers in [text] that are not known (to warn before narrating). */
    fun unknown(text: String): List<String> = MARKER.findAll(text).filter { marker(it.groupValues[1]) == null }.map { it.value }.toList()

    fun hasMarkers(text: String) = MARKER.containsMatchIn(text)

    /** [text] split into speech and markers; unknown markers are dropped, empty speech is skipped. */
    fun parse(text: String): List<Seg> {
        val out = ArrayList<Seg>()
        var pos = 0
        for (m in MARKER.findAll(text)) {
            val before = text.substring(pos, m.range.first)
            if (before.isNotBlank()) out.add(Speech(before.trim()))
            marker(m.groupValues[1])?.let(out::add)
            pos = m.range.last + 1
        }
        val rest = text.substring(pos)
        if (rest.isNotBlank()) out.add(Speech(rest.trim()))
        return out
    }
}
