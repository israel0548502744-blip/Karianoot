package com.karianoot.engine

/**
 * Hebrew numbers to words, so "היחס 2.5", "3-1", "21:45" and "70%" are read out instead of skipped.
 * A line-by-line port of `hebrew-num2words` 0.1.0 (Apache-2.0, Max Melichov), which RenikudPlus runs
 * before its model (the model never saw a digit). Checked against the Python package in
 * HebrewNumbersTest (golden cases from tools/numbers_golden.py).
 */
object HebrewNumbers {
    const val MASC = "m"
    const val FEM = "f"

    private val MASC_UNITS = listOf("אפס", "אחד", "שניים", "שלושה", "ארבעה", "חמישה", "שישה", "שבעה", "שמונה", "תשעה", "עשרה")
    private val FEM_UNITS = listOf("אפס", "אחת", "שתיים", "שלוש", "ארבע", "חמש", "שש", "שבע", "שמונה", "תשע", "עשר")
    private val MASC_CONSTRUCT = mapOf(2 to "שני", 3 to "שלושת", 4 to "ארבעת", 5 to "חמשת", 6 to "ששת", 7 to "שבעת",
        8 to "שמונת", 9 to "תשעת", 10 to "עשרת")
    private val FEM_CONSTRUCT = mapOf(2 to "שתי")
    private val MASC_TEENS = listOf("אחד עשר", "שנים עשר", "שלושה עשר", "ארבעה עשר", "חמישה עשר",
        "שישה עשר", "שבעה עשר", "שמונה עשר", "תשעה עשר")
    private val FEM_TEENS = listOf("אחת עשרה", "שתים עשרה", "שלוש עשרה", "ארבע עשרה", "חמש עשרה",
        "שש עשרה", "שבע עשרה", "שמונה עשרה", "תשע עשרה")
    private val TENS = mapOf(2 to "עשרים", 3 to "שלושים", 4 to "ארבעים", 5 to "חמישים",
        6 to "שישים", 7 to "שבעים", 8 to "שמונים", 9 to "תשעים")
    private val HUNDREDS = mapOf(1 to "מאה", 2 to "מאתיים", 3 to "שלוש מאות", 4 to "ארבע מאות", 5 to "חמש מאות",
        6 to "שש מאות", 7 to "שבע מאות", 8 to "שמונה מאות", 9 to "תשע מאות")
    private val ORD_MASC = mapOf(1 to "ראשון", 2 to "שני", 3 to "שלישי", 4 to "רביעי", 5 to "חמישי",
        6 to "שישי", 7 to "שביעי", 8 to "שמיני", 9 to "תשיעי", 10 to "עשירי")
    private val ORD_FEM = mapOf(1 to "ראשונה", 2 to "שנייה", 3 to "שלישית", 4 to "רביעית", 5 to "חמישית",
        6 to "שישית", 7 to "שביעית", 8 to "שמינית", 9 to "תשיעית", 10 to "עשירית")
    private val MONTHS = mapOf(1 to "ינואר", 2 to "פברואר", 3 to "מרץ", 4 to "אפריל", 5 to "מאי", 6 to "יוני",
        7 to "יולי", 8 to "אוגוסט", 9 to "ספטמבר", 10 to "אוקטובר", 11 to "נובמבר", 12 to "דצמבר")
    private val MONTH_NAMES = MONTHS.values.toSet()
    private val FRACTIONS = mapOf(
        (1 to 2) to "חצי", (1 to 3) to "שליש", (1 to 4) to "רבע", (2 to 3) to "שני שלישים",
        (3 to 4) to "שלושת רבעי", (1 to 5) to "חמישית", (2 to 5) to "שתי חמישיות",
        (3 to 5) to "שלוש חמישיות", (4 to 5) to "ארבע חמישיות", (1 to 6) to "שישית",
        (1 to 8) to "שמינית", (3 to 8) to "שלוש שמיניות", (1 to 10) to "עשירית",
    )

    private fun vav(word: String) = "ו$word"
    private fun units(n: Int, g: String) = (if (g == MASC) MASC_UNITS else FEM_UNITS)[n]
    private fun teens(n: Int, g: String) = (if (g == MASC) MASC_TEENS else FEM_TEENS)[n - 11]

    private fun thousandsWords(k: Long): List<String> = when {
        k == 1L -> listOf("אלף")
        k == 2L -> listOf("אלפיים")
        k in 3..10 -> listOf(MASC_CONSTRUCT.getValue(k.toInt()), "אלפים")
        else -> cardinal(k, MASC).split(" ") + "אלף"
    }

    private fun subThousandGroups(n0: Int, g: String): MutableList<List<String>> {
        var n = n0
        val groups = ArrayList<List<String>>()
        if (n >= 100) { groups.add(HUNDREDS.getValue(n / 100).split(" ")); n %= 100 }
        if (n >= 20) {
            val tail = arrayListOf(TENS.getValue(n / 10))
            if (n % 10 != 0) tail.add(vav(units(n % 10, g)))
            groups.add(tail)
        } else if (n >= 11) groups.add(teens(n, g).split(" "))
        else if (n >= 1) groups.add(listOf(units(n, g)))
        return groups
    }

    fun cardinal(n0: Long, gender: String = FEM, construct: Boolean = false): String {
        if (n0 < 0) return "מינוס " + cardinal(-n0, gender, construct)
        if (n0 == 0L) return "אפס"
        if (construct) {
            val table = if (gender == MASC) MASC_CONSTRUCT else FEM_CONSTRUCT
            if (n0 <= 10) table[n0.toInt()]?.let { return it }
        }
        var n = n0
        val groups = ArrayList<List<String>>()
        if (n >= 1000) { groups.add(thousandsWords(n / 1000)); n %= 1000 }
        groups.addAll(subThousandGroups(n.toInt(), gender))
        if (groups.size > 1 && groups.last().none { it.startsWith("ו") }) {
            val last = groups.last()
            groups[groups.size - 1] = listOf(vav(last[0])) + last.drop(1)
        }
        return groups.flatten().joinToString(" ")
    }

    fun cardinal(n: Int, gender: String = FEM, construct: Boolean = false) = cardinal(n.toLong(), gender, construct)

    fun ordinal(n: Int, gender: String = MASC, definite: Boolean = false): String {
        val table = if (gender == MASC) ORD_MASC else ORD_FEM
        val word = table[n] ?: cardinal(n, if (gender == FEM) FEM else MASC)
        return if (definite) "ה$word" else word
    }

    fun spellDigits(digits: String) = digits.map { FEM_UNITS[it - '0'] }.joinToString(" ")

    // --- gender of the counted noun ---
    private val FEM_IM_PLURALS = setOf("שנים", "נשים", "מילים", "ביצים", "דבורים", "חיטים", "תאנים", "פעמים",
        "אבנים", "עצים", "יונים", "צפרניים", "שעורים")
    private val MASC_OT_PLURALS = setOf("שולחנות", "כיסאות", "מקומות", "לילות", "קולות", "חלומות", "רחובות",
        "אבות", "שמות", "לוחות", "קירות", "מטבעות", "שבועות", "רעיונות",
        "חלונות", "זנבות", "מזלגות", "סכינים", "אריות", "נכסים")
    private val NOUN_GENDER: Map<String, String> = HashMap<String, String>().apply {
        for (w in listOf("שעה", "שעות", "דקה", "דקות", "שניה", "שניות", "שנה", "שנות", "מנה", "מנות", "כוס", "כוסות",
            "קומה", "קומות", "פעם", "פעמים", "בת", "בנות", "מעלה", "מעלות", "טיסה", "טיסות", "אישה", "נשים",
            "דירה", "דירות", "עונה", "עונות", "שורה", "שורות", "חנות", "חנויות", "מסעדה", "מסעדות", "פגישה", "פגישות",
            "יחידה", "יחידות", "משימה", "משימות", "מחברת", "מחברות", "מעטפה", "מעטפות", "חולצה", "חולצות", "מיטה", "מיטות",
            "דלת", "דלתות", "רגל", "רגליים", "יד", "ידיים", "עין", "עיניים", "אוזן", "אוזניים", "עיר", "ערים",
            "ארץ", "דרך", "נפש", "כיתה", "כיתות", "אגורה", "אגורות")) put(w, FEM)
        for (w in listOf("שקל", "שקלים", "ש\"ח", "דולר", "דולרים", "יורו", "אירו",
            "יום", "ימים", "ימי", "חודש", "חודשים", "חודשי", "שבוע", "שבועות", "איש", "אנשים", "ילד", "ילדים",
            "בן", "בנים", "חבר", "חברים", "עובד", "עובדים", "ספר", "ספרים", "כלב", "כלבים", "חתול", "חתולים",
            "תפוח", "תפוחים", "כרטיס", "כרטיסים", "מטר", "מטרים", "קילומטר", "קילו", "ליטר",
            "מיליון", "מיליארד", "אלף", "אחוז", "אחוזים", "מוצר", "מוצרים", "משתתף", "משתתפים",
            "בניין", "בניינים", "משרד", "משרדים", "סטודנט", "סטודנטים", "דייר", "דיירים",
            "עט", "עטים", "מכתב", "מכתבים", "כדור", "כדורים", "נרשם", "נרשמים", "פועל", "פועלים",
            "ק\"ג", "ק\"מ", "מ\"ר", "קמ\"ש", "מ\"מ", "ס\"מ")) put(w, MASC)
    }
    private val PREFIX_RE = Regex("^(?:[בכלמשו]?ה|[בכלמשוה])")

    private fun stripPrefix(word: String): String {
        if (word.length > 3) {
            val s = PREFIX_RE.replaceFirst(word, "")
            if (s.length >= 2) return s
        }
        return word
    }

    private fun strip(s: String, chars: String) = s.trim { it in chars }

    fun nounGender(word0: String): String? {
        val word = strip(word0, ".,!?;:\"'()[]—–-")
        if (word.isEmpty()) return null
        for (c in listOf(word, stripPrefix(word))) {
            NOUN_GENDER[c]?.let { return it }
            if (c in FEM_IM_PLURALS) return FEM
            if (c in MASC_OT_PLURALS) return MASC
        }
        val stem = stripPrefix(word)
        if (stem.length < 3) return null
        if (stem.endsWith("יות") || stem.endsWith("ות")) return FEM
        if (stem.endsWith("ים") || stem.endsWith("יים")) return MASC
        return null
    }

    // --- context vocabularies ---
    private val CODE_NOUNS = setOf("קוד", "מספר", "מיקוד", "סיסמה", "סיסמא", "סיומת", "ברקוד", "טלפון",
        "שלוחה", "סניף", "מזהה", "כרטיס", "אשראי", "פוליסה", "הזמנה",
        "משלוח", "זהות", "ת.ז", "ת\"ז", "רישיון", "אימות", "פנימי", "סודי",
        "סידורי", "חשאי", "אישי", "בנק", "גישה", "ז", "מנעול")
    private val ORDINAL_NOUNS = setOf("קומה", "פעם", "מקום", "פרק", "חלק", "עונה", "טסט", "שורה", "שלב",
        "כיתה", "רבעון", "סיבוב", "מחזור")
    private val HOUR_CUES = setOf("שעה", "השעה", "בשעה", "לשעה", "שעון", "השעון")
    private val DAY_OF_MONTH_CUES = setOf("לחודש", "בחודש", "החודש")
    private val YEAR_CUES = setOf("שנת", "בשנת", "משנת", "לשנת", "שנה", "השנה")
    private const val PUNCT_TAIL = ".,!?;:\"')]…"

    private fun hourWord(h0: Int): String {
        var h = Math.floorMod(h0, 24)
        if (h == 0) h = 12
        if (h > 12) h -= 12
        return cardinal(h, FEM)
    }

    fun expandTime(hh: Int, mm: Int): String {
        val hour = hourWord(hh)
        return when {
            mm == 0 -> hour
            mm == 15 -> "$hour ורבע"
            mm == 30 -> "$hour וחצי"
            mm == 45 -> "רבע ל" + hourWord(hh + 1)
            mm < 30 -> hour + " " + vav(cardinal(mm, MASC))
            else -> cardinal(60 - mm, MASC) + " ל" + hourWord(hh + 1)
        }
    }

    fun expandDate(day: Int, month: Int, year: Int?): String {
        var out = cardinal(day, MASC) + " ב" + MONTHS.getValue(month)
        if (year != null) out += " " + cardinal(year, FEM)
        return out
    }

    private fun decimalWords(intPart: String, fp: String, gender: String): String {
        val ip = intPart.toLong()
        if (fp == "5") return if (ip == 0L) "חצי" else cardinal(ip, gender) + " וחצי"
        if (fp.length == 2) return cardinal(ip, if (ip != 1L) FEM else MASC) + " " + cardinal(fp.toLong(), FEM)
        val head = if (ip == 1L) cardinal(ip, MASC) else cardinal(ip, FEM)
        return head + " נקודה " + fp.map { FEM_UNITS[it - '0'] }.joinToString(" ")
    }

    private fun groupedDigits(groups: List<String>): String {
        if (groups.isNotEmpty() && groups[0] in setOf("1", "*") && groups.size > 1 && groups[1].length == 3) {
            val out = arrayListOf(if (groups[0].length == 1) MASC_UNITS[groups[0].toInt()] else spellDigits(groups[0]))
            for (g in groups.drop(1)) out.add(cardinal(g.toLong(), FEM))
            return out.joinToString(" ")
        }
        return groups.joinToString(" ") { spellDigits(it) }
    }

    private val TOKEN_RE = Regex("^([\\u0590-\\u05EA\"']{0,4})(-?)(\\d[\\d.,:/%\\-]*?)([.,!?;:\"')\\]…]*)$")
    private val DIGITS = Regex("\\d+")
    private fun isDigits(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' }

    private fun nextNounGender(next: List<String>): String? = next.firstOrNull()?.let { nounGender(it) }

    private fun isCodeContext(prev0: List<String>): Boolean {
        var prev = prev0
        for (i in prev.indices.reversed()) if (prev[i].any { it.isDigit() }) { prev = prev.subList(i + 1, prev.size); break }
        for (w in prev.takeLast(3)) {
            val base = strip(w, "$PUNCT_TAIL-")
            if (base in CODE_NOUNS || stripPrefix(base) in CODE_NOUNS) return true
        }
        return false
    }

    private fun prevBase(prev: List<String>, k: Int = 1) = if (prev.size < k) "" else strip(prev[prev.size - k], "$PUNCT_TAIL-")

    private fun monthFollows(next: List<String>): Boolean {
        if (next.isEmpty()) return false
        val w = strip(next[0], PUNCT_TAIL)
        if (w in DAY_OF_MONTH_CUES) return true
        val s = Regex("^[בלמה]").replaceFirst(w, "")
        return w in MONTH_NAMES || s in MONTH_NAMES
    }

    private fun expandInteger(digits: String, pre: String, prev: List<String>, next: List<String>): String {
        val nn = digits.toLongOrNull()
        if (digits.length >= 5 || (digits.length >= 3 && isCodeContext(prev)) || (digits.length > 1 && digits[0] == '0'))
            return spellDigits(digits)
        val n = nn!!.toInt()
        if (prevBase(prev) in YEAR_CUES) return cardinal(n, FEM)
        if (monthFollows(next) && n in 1..31) return cardinal(n, MASC)
        val p = prevBase(prev)
        val ps = if (p in ORDINAL_NOUNS) p else stripPrefix(p)
        if (pre.endsWith("ה") && n <= 999) {
            val g = NOUN_GENDER[ps] ?: nounGender(p) ?: MASC
            return ordinal(n, g)
        }
        if (ps in ORDINAL_NOUNS && n <= 10) return ordinal(n, NOUN_GENDER[ps] ?: MASC)
        val g = nextNounGender(next)
        if (g != null) return if (n == 2) cardinal(2, g, construct = true) else cardinal(n, g)
        if (pre in setOf("ב", "מ", "ל") || p in setOf("עד", "ועד") || p in HOUR_CUES || prevBase(prev, 2) in HOUR_CUES) {
            if (n in 0..24 && !isCodeContext(prev)) return hourWord(n)
        }
        return cardinal(n, if (n == 1) MASC else FEM)
    }

    private fun expandBody(body: String, pre: String, prev: List<String>, next: List<String>): String {
        if (body.endsWith("%")) {
            var core = body.dropLast(1)
            if (Regex("\\d{1,3}(?:,\\d{3})+").matches(core)) core = core.replace(",", "")
            val tail = if (next.isNotEmpty() && strip(next[0], PUNCT_TAIL) in setOf("אחוז", "אחוזים")) "" else " אחוז"
            if ("." in core) {
                val ip = core.substringBefore("."); val fp = core.substringAfter(".")
                require(isDigits(ip) && isDigits(fp))
                return decimalWords(ip, fp, MASC) + tail
            }
            require(isDigits(core))
            return cardinal(core.toLong(), MASC) + tail
        }
        Regex("(\\d{1,2}):(\\d{2})").matchEntire(body)?.let { return expandTime(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        Regex("(\\d{1,2})/(\\d{1,4})").matchEntire(body)?.let { m ->
            val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
            FRACTIONS[a to b]?.let { return it }
            if (b in 1..12 && a in 1..31) return expandDate(a, b, null)
            return cardinal(a, FEM) + " " + cardinal(b, FEM)
        }
        if ("-" in body) {
            val groups = body.split("-")
            if (groups.all { isDigits(it) } && groups.size >= 2) {
                if (groups.size >= 3 || groups.any { it.length >= 5 }) return groupedDigits(groups)
                if (groups.all { it.length <= 2 }) return groups.joinToString(" ") { cardinal(it.toLong(), FEM) }
                return groupedDigits(groups)
            }
        }
        if (Regex("\\d{1,3}(?:,\\d{3})+").matches(body)) {
            val n = body.replace(",", "").toLong()
            val g = nextNounGender(next)
            if (n >= 1_000_000) {
                val head = n / 1_000_000; val rest = n % 1_000_000
                val word = if (head == 1L) "מיליון" else cardinal(head, MASC, construct = true) + " מיליון"
                return if (rest == 0L) word else word + " " + cardinal(rest, g ?: MASC)
            }
            return cardinal(n, g ?: MASC)
        }
        Regex("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})").matchEntire(body)?.let { m ->
            val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt(); val y = m.groupValues[3].toInt()
            if (d in 1..31 && mo in 1..12) return expandDate(d, mo, y)
        }
        Regex("(\\d{1,2})\\.(\\d{4})").matchEntire(body)?.let { m ->
            val mo = m.groupValues[1].toInt()
            if (mo in 1..12) return "ב" + MONTHS.getValue(mo) + " " + cardinal(m.groupValues[2].toLong(), FEM)
        }
        Regex("(\\d+)\\.(\\d+)").matchEntire(body)?.let { m ->
            val ipS = m.groupValues[1]; val fpS = m.groupValues[2]
            val ip = ipS.toLong(); val fp = fpS.toLong()
            val clockCue = prevBase(prev) in HOUR_CUES
            val unitFollows = next.isNotEmpty() && nounGender(next[0]) != null
            if (!clockCue && pre in setOf("ב", "ל", "ה") && fpS.length <= 2 && fp in 1..12 && ip in 1..31 && !unitFollows)
                return expandDate(ip.toInt(), fp.toInt(), null)
            if (clockCue && fpS.length == 2) return expandTime(ip.toInt(), fp.toInt())
            return decimalWords(ipS, fpS, nextNounGender(next) ?: FEM)
        }
        if (isDigits(body)) return expandInteger(body, pre, prev, next)
        return DIGITS.replace(body) { cardinal(it.value.toLong(), FEM) }
    }

    fun expandToken(token: String, prev: List<String> = emptyList(), next: List<String> = emptyList()): String {
        if (token.none { it.isDigit() }) return token
        val m = TOKEN_RE.matchEntire(token) ?: return try {
            DIGITS.replace(token) { cardinal(it.value.toLong(), FEM) }
        } catch (e: Exception) { token }
        val pre = m.groupValues[1]; val body = m.groupValues[3]; val post = m.groupValues[4]
        val words = try { expandBody(body, pre, prev, next) } catch (e: Exception) { return token }
        return pre + words + post
    }

    /** Expands every number in [text]; text without digits comes back unchanged. */
    fun normalize(text: String): String {
        if (text.none { it in '0'..'9' }) return text
        val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return tokens.mapIndexed { i, t ->
            if (t.any { it in '0'..'9' }) expandToken(t, tokens.subList(0, i), tokens.subList(i + 1, tokens.size)) else t
        }.joinToString(" ")
    }
}
