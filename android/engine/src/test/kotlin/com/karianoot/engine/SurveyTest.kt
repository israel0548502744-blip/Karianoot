package com.karianoot.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Prints how each line of KARIANOOT_SURVEY is spoken (TextPrep, then the number reader). Local review tool. */
class SurveyTest {
    @Test fun survey() {
        val f = System.getenv("KARIANOOT_SURVEY")?.takeIf { it.isNotEmpty() }?.let(::File)
        assumeTrue(f != null && f.exists())
        File(f!!.path + ".out").printWriter().use { out ->
            for (l in f.readLines().filter { it.isNotBlank() }) out.println("$l\t${HebrewNumbers.normalize(TextPrep.apply(l))}")
        }
    }
}
