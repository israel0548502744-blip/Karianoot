package com.karianoot.engine

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/** A float32 array with its shape, read from a NumPy .npy (or an entry of an .npz). */
class NpArray(val shape: IntArray, val data: FloatArray) {
    val size get() = data.size
}

object Npy {
    fun read(file: File): NpArray = file.inputStream().use { read(it) }

    fun readNpz(file: File): Map<String, NpArray> {
        val out = HashMap<String, NpArray>()
        ZipFile(file).use { z ->
            for (e in z.entries()) {
                if (!e.name.endsWith(".npy")) continue
                z.getInputStream(e).use { out[e.name.removeSuffix(".npy")] = read(it) }
            }
        }
        return out
    }

    fun read(input: InputStream): NpArray {
        val bytes = input.readBytes()
        require(bytes[0] == 0x93.toByte() && String(bytes, 1, 5) == "NUMPY") { "not a .npy file" }
        val major = bytes[6].toInt()
        val headerLen: Int
        val headerStart: Int
        if (major == 1) {
            headerLen = (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
            headerStart = 10
        } else {
            headerLen = ByteBuffer.wrap(bytes, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
            headerStart = 12
        }
        val header = String(bytes, headerStart, headerLen, Charsets.ISO_8859_1)
        val descr = Regex("'descr':\\s*'([^']+)'").find(header)!!.groupValues[1]
        require(!header.contains("'fortran_order': True")) { "fortran order not supported" }
        val shapeStr = Regex("'shape':\\s*\\(([^)]*)\\)").find(header)!!.groupValues[1]
        val shape = shapeStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { it.toInt() }.toIntArray()
        val count = shape.fold(1) { a, b -> a * b }
        val buf = ByteBuffer.wrap(bytes, headerStart + headerLen, bytes.size - headerStart - headerLen)
            .order(if (descr.startsWith(">")) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        val data = FloatArray(count)
        when (descr.substring(1)) {
            "f4" -> buf.asFloatBuffer().get(data)
            "f8" -> { val d = buf.asDoubleBuffer(); for (i in 0 until count) data[i] = d.get(i).toFloat() }
            "i8" -> { val d = buf.asLongBuffer(); for (i in 0 until count) data[i] = d.get(i).toFloat() }
            "i4" -> { val d = buf.asIntBuffer(); for (i in 0 until count) data[i] = d.get(i).toFloat() }
            else -> error("unsupported dtype $descr")
        }
        return NpArray(shape, data)
    }
}
