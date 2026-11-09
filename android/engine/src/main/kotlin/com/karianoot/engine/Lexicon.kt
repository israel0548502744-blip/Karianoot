package com.karianoot.engine

/**
 * Football names and terms the Hebrew G2P reads wrong, with the way they are said in Israel, as stressed IPA in the
 * G2P's own symbols (the stress mark goes right before the stressed vowel). The G2P copies non-Hebrew text through
 * unchanged, so a word replaced here by its IPA reaches the voice exactly. Checked one by one against the G2P's own
 * reading (e.g. תיקו came out "teko", ארסנל "arsenAl", מבאפה "mibapa", נתניה "natanja").
 *
 * A word may carry Hebrew prefixes ("לארסנל", "ב-תיקו", "ולמבאפה"); they are read too.
 * Applied just before the G2P, so the text the user sees is untouched; the user's own fixes run earlier and win.
 */
object Lexicon {
    val WORDS: Map<String, String> = mapOf(
        // Betting
        "תיקו" to "tˈeku",
        "הנדיקפ" to "hˈendikep",
        "אנדרדוג" to "ʔˈandeʁdoɡ",
        "הבקעה" to "havkaʔˈa",
        "סטייק" to "stˈejk",
        "בנקרול" to "bˈankʁol",
        "ביו" to "bˈajo",
        // Israeli
        "נתניה" to "netanjˈa",
        // Clubs
        "ארסנל" to "ʔˈaʁsenal",
        "באיירן" to "bˈajeʁn",
        "בורוסיה" to "boʁˈusja",
        "דורטמונד" to "dˈoʁtmund",
        "באייר" to "bˈajeʁ",
        "לברקוזן" to "leveʁkˈuzen",
        "בנפיקה" to "benfˈika",
        "אייאקס" to "ʔajˈaks",
        "גלאטסראיי" to "ɡalatasaʁˈaj",
        "גלטסראיי" to "ɡalatasaʁˈaj",
        "פנרבחצ'ה" to "feneʁbˈaχtʃe",
        "פנרבחצה" to "feneʁbˈaχtʃe",
        "פיורנטינה" to "fjoʁentˈina",
        "ויאריאל" to "vijaʁeʔˈal",
        "סביליה" to "sevˈilja",
        "בטיס" to "bˈetis",
        "לייפציג" to "lˈajptsiɡ",
        "ליון" to "lijˈon",
        "אלקמאר" to "ʔˈalkmaʁ",
        "שחטאר" to "ʃaχtˈaʁ",
        "טרבזונספור" to "tʁabzˈonspoʁ",
        "סוסיאדד" to "sosjedˈad",
        "חירונה" to "χiʁˈona",
        "חטאפה" to "χetˈafe",
        "אודינזה" to "ʔudinˈeze",
        "ג'נואה" to "dʒˈenoa",
        "סאות'המפטון" to "saʔuthˈampton",
        "ברנטפורד" to "bʁˈentfoʁd",
        "בורנמות'" to "bˈoʁnmut",
        "פרייבורג" to "fʁˈajbuʁɡ",
        "אוניון ברלין" to "ʔˈunjon beʁlˈin",
        "מונפלייה" to "monpeljˈe",
        "בודה גלימט" to "bˈode ɡlˈimt",
        "מאלמו" to "mˈalme",
        "קרבאך" to "kaʁabˈaχ",
        "בזל" to "bˈazel",
        "גנק" to "ɡˈenk",
        // Leagues
        "פרמייר" to "pʁemjˈeʁ",
        "סרייה" to "sˈeʁja",
        // Players and coaches
        "מבאפה" to "embˈape",
        "בלינגהאם" to "bˈelinɡham",
        "סלאח" to "salˈaχ",
        "ימאל" to "jamˈal",
        "לאמין" to "lamˈin",
        "בראונה" to "bʁˈojne",
        "אודגור" to "ʔˈodeɡoʁ",
        "מוסיאלה" to "muzjˈala",
        "ואן" to "vˈan",
        "אליסון" to "ʔˈalison",
        "קורטואה" to "kuʁtuʔˈa",
        "נוייר" to "nˈojeʁ",
        "אוסימן" to "ʔosˈimen",
        "קוורצחליה" to "kvaʁatsχˈelja",
        "דמבלה" to "dembˈele",
        "אלוורז" to "ʔalvˈaʁes",
        "רפיניה" to "ʁafˈinja",
        "אולמו" to "ʔˈolmo",
        "וירץ" to "vˈiʁts",
        "גווארדיולה" to "ɡvaʁdjˈola",
        "סלוט" to "slˈot",
        "ולוורדה" to "valvˈeʁde",
        "צ'ואמני" to "tʃuʔamˈeni",
        "לאוטרו" to "laʔutˈaʁo",
        "לאוטארו" to "laʔutˈaʁo",
        "הארי קיין" to "hˈeʁi kˈejn",
        "סאליבה" to "salˈiba",
        "ג'סוס" to "ʒezˈus",
        "נונייס" to "nˈunjes",
        "דיאס" to "dˈijas",
        "מק אליסטר" to "mˈak ʔˈalisteʁ",
    )

    private const val HEB = "\\u05D0-\\u05EA"
    private val SINGLE = mapOf('ו' to "ve", 'ב' to "be", 'ל' to "le", 'מ' to "me", 'ה' to "ha", 'כ' to "ke", 'ש' to "ʃe")
    // After ו the next prefix letter loses its dagesh: וּבְ = uve, וּכְ = uχe.
    private val SOFT = mapOf('ב' to "ve", 'כ' to "χe")

    /** The sound of up to two prefix letters ("ול" -> "ule", "בה" -> "ba"). */
    fun prefix(p: String): String = when (p.length) {
        0 -> ""
        1 -> SINGLE.getValue(p[0])
        else -> when {
            p[1] == 'ה' && p[0] in "בלכ" -> SINGLE.getValue(p[0]).dropLast(1) + "a"   // the article merges: בַּ, לַ, כַּ
            p[0] == 'ו' && p[1] == 'ה' -> "veha"
            p[0] == 'ו' -> "u" + (SOFT[p[1]] ?: SINGLE.getValue(p[1]))
            else -> SINGLE.getValue(p[0]) + SINGLE.getValue(p[1])
        }
    }

    private val PATTERN = Regex(
        "(?<![$HEB'])([ובלמהכש]{0,2})-?(" + WORDS.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } +
            ")(?![$HEB'])"
    )

    fun apply(text: String): String {
        val t = text.replace('׳', '\'')
        return PATTERN.replace(t) { m ->
            val pre = prefix(m.groupValues[1]); val ipa = WORDS.getValue(m.groupValues[2])
            // A prefix before a vowel keeps a glottal stop, as the G2P reads "לאמבאפה": le-ʔembape.
            pre + (if (pre.isNotEmpty() && ipa.first() in "aeiou") "ʔ" else "") + ipa
        }
    }
}
