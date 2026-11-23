package com.karianoot.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** ocr-probe.yml: what SlipText makes of Tesseract's reading of test slips (KARIANOOT_OCR = dir of *.txt). */
class SlipOcrTest {
    @Test fun parseOcrOutputs() {
        val dir = System.getenv("KARIANOOT_OCR")?.takeIf { it.isNotEmpty() }?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        for (f in dir!!.listFiles { x -> x.extension == "txt" }!!.sortedBy { it.name }) {
            val p = SlipText.parse(f.readText())
            println("CHECK ${f.name}: ${p.size} tips: " + p.joinToString(" | ") { "${it.match} / ${it.pick} / ${it.odds}" })
        }
    }
}
