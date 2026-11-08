package com.karianoot.engine

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Kotlin number reader gives exactly what the Python `hebrew-num2words` gives. */
class HebrewNumbersTest {
    @Test fun matchesPython() {
        val cases = JSONArray(javaClass.getResource("/numbers_golden.json")!!.readText())
        val bad = ArrayList<String>()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val got = HebrewNumbers.normalize(c.getString("text"))
            if (got != c.getString("norm")) bad.add("${c.getString("text")}\n   python: ${c.getString("norm")}\n   kotlin: $got")
        }
        bad.forEach { println("NUM FAIL $it") }
        assertEquals("number mismatches (of ${cases.length()})", 0, bad.size)
    }

    @Test fun bettingExamples() {
        assertEquals("היחס הוא שתיים וחצי", HebrewNumbers.normalize("היחס הוא 2.5"))
        assertEquals("ניצחון שלוש אחת", HebrewNumbers.normalize("ניצחון 3-1"))
    }
}
