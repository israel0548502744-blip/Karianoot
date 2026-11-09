package com.karianoot.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class RvqTest {
    /** Packs like qwentts.cpp rvq_pack_codes (11 bits, LSB-first) and reads back. */
    @Test fun unpacksElevenBitCodes() {
        val codes = IntArray(16 * 5) { (it * 137) % 2048 }
        var acc = 0L; var bits = 0
        val out = ArrayList<Byte>()
        for (c in codes) {
            acc = acc or ((c.toLong() and 2047) shl bits); bits += 11
            while (bits >= 8) { out.add((acc and 0xFF).toByte()); acc = acc ushr 8; bits -= 8 }
        }
        if (bits > 0) out.add((acc and 0xFF).toByte())
        assertArrayEquals(codes, QwenTts.readRvq(out.toByteArray(), 16))
    }
}
