package com.karianoot.engine

/**
 * Stressed IPA back into Hebrew letters with niqqud, so the user can SEE how a word will be said: "ʔˈaʁsenal" ->
 * אַרְ·סֶ·נַל with the first syllable marked as stressed. Foreign sounds keep the usual Israeli spelling (ג׳, צ׳, ז׳),
 * k is ק, t is ט (as in transliterated names), v is ב inside a word and ו at its start. Also makes the other stress choices of a word ([stressVariants]),
 * for "which one is right?" by ear.
 */
object IpaHebrew {
    data class Syllable(val text: String, val stressed: Boolean)

    private const val PATAH = 'ַ'; private const val SEGOL = 'ֶ'; private const val HIRIQ = 'ִ'
    private const val SHVA = 'ְ'; private const val DAGESH = 'ּ'; private const val HOLAM = 'ֹ'; private const val SHIN_DOT = 'ׁ'
    private const val VOWELS = "aeiou"

    /** IPA sound -> letters (with dagesh / shin dot where it changes the sound). */
    private val CONS = linkedMapOf(
        "tʃ" to "צ׳", "dʒ" to "ג׳", "dʃ" to "ג׳", "ts" to "צ",
        "b" to "ב$DAGESH", "v" to "ו", "ɡ" to "ג", "g" to "ג", "d" to "ד", "h" to "ה", "z" to "ז", "ʒ" to "ז׳", "χ" to "ח",
        "x" to "ח", "t" to "ט", "j" to "י", "k" to "ק", "l" to "ל", "m" to "מ", "n" to "נ", "s" to "ס", "ʔ" to "א",
        "p" to "פ$DAGESH", "f" to "פ", "ʁ" to "ר", "r" to "ר", "ʃ" to "ש$SHIN_DOT", "w" to "ו",
    )
    private val FINAL = mapOf("מ" to "ם", "נ" to "ן", "פ" to "ף", "צ" to "ץ", "כ" to "ך")

    private sealed interface Ph
    private data class C(val letters: String, val v: Boolean = false) : Ph
    private data class V(val v: Char, val stressed: Boolean) : Ph

    private fun phones(ipa: String): List<Ph> {
        val out = ArrayList<Ph>()
        var i = 0; var stress = false
        val s = ipa.lowercase()
        while (i < s.length) {
            val ch = s[i]
            when {
                ch == 'ˈ' -> { stress = true; i++ }
                ch in VOWELS -> { out.add(V(ch, stress)); stress = false; i++ }
                else -> {
                    val two = if (i + 1 < s.length) s.substring(i, i + 2) else ""
                    val hit = CONS[two]?.let { two to it } ?: CONS[ch.toString()]?.let { ch.toString() to it }
                    if (hit != null) { out.add(C(hit.second, hit.first == "v")); i += hit.first.length } else i++
                }
            }
        }
        return out
    }

    /** One IPA word -> its syllables in Hebrew with niqqud (punctuation dropped). */
    fun render(ipaWord: String): List<Syllable> {
        val ph = phones(ipaWord)
        if (ph.isEmpty()) return emptyList()
        // Syllables: a vowel and the consonants before it; of a cluster between two vowels, the first closes the earlier one.
        val groups = ArrayList<MutableList<Ph>>()
        var cur = ArrayList<Ph>()
        var k = 0
        while (k < ph.size) {
            val p = ph[k]
            if (p is V) {
                cur.add(p)
                // Look ahead: consonants until the next vowel.
                var j = k + 1; while (j < ph.size && ph[j] is C) j++
                val cons = j - (k + 1)
                val keep = if (j >= ph.size) cons else if (cons >= 2) 1 else 0
                for (q in 0 until keep) cur.add(ph[k + 1 + q])
                groups.add(cur); cur = ArrayList()
                k += 1 + keep
            } else { cur.add(p); k++ }
        }
        if (cur.isNotEmpty()) { if (groups.isEmpty()) groups.add(cur) else groups.last().addAll(cur) }
        val lastC = ph.indexOfLast { it is C }.takeIf { it == ph.size - 1 }
        var index = 0
        return groups.map { g ->
            val sb = StringBuilder()
            var stressed = false
            g.forEachIndexed { gi, p ->
                val global = index + gi
                when (p) {
                    is C -> {
                        val next = ph.getOrNull(global + 1)
                        var letters = p.letters
                        // v is ב (בֿ) inside a word, as in טוב, ערב; only at the start of a word is it ו.
                        if (letters == "ו" && p.v && global > 0) letters = "ב"
                        if (global == lastC) letters = FINAL[letters] ?: letters
                        sb.append(letters)
                        when {
                            next is V -> {}
                            global == ph.size - 1 -> {}
                            else -> sb.append(SHVA)
                        }
                    }
                    is V -> {
                        stressed = stressed || p.stressed
                        val prevIsC = ph.getOrNull(global - 1) is C
                        if (!prevIsC) sb.append('א')
                        when (p.v) {
                            'a' -> sb.append(PATAH)
                            'e' -> sb.append(SEGOL)
                            'i' -> sb.append(HIRIQ).append('י')
                            'o' -> sb.append('ו').append(HOLAM)
                            'u' -> sb.append('ו').append(DAGESH)
                        }
                    }
                }
            }
            index += g.size
            Syllable(java.text.Normalizer.normalize(sb, java.text.Normalizer.Form.NFC), stressed)
        }
    }

    fun renderText(ipaWord: String) = render(ipaWord).joinToString("") { it.text }

    /** The word with the stress on each of its vowels in turn (the current one first). */
    fun stressVariants(ipaWord: String): List<String> {
        val bare = ipaWord.replace("ˈ", "")
        val vowelAt = bare.indices.filter { bare[it] in VOWELS }
        val all = vowelAt.map { i -> bare.substring(0, i) + "ˈ" + bare.substring(i) }
        return (listOf(ipaWord) + all).distinct().take(5)
    }
}
