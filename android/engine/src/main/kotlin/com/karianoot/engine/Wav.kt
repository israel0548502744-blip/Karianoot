package com.karianoot.engine

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

object Wav {
    /** Mono 16-bit PCM WAV. */
    fun write(file: File, samples: FloatArray, sampleRate: Int) {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) data.putShort((s.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + samples.size * 2); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(sampleRate); h.putInt(sampleRate * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(samples.size * 2)
        RandomAccessFile(file, "rw").use { f -> f.setLength(0); f.write(h.array()); f.write(data.array()) }
    }

    /** Reads a mono 16-bit PCM WAV written by [write] -> (samples, sample rate). */
    fun read(file: File): Pair<FloatArray, Int> {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val rate = b.getInt(24)
        var pos = 12
        while (pos + 8 <= b.limit()) {
            val id = String(ByteArray(4) { b.get(pos + it) }); val len = b.getInt(pos + 4)
            if (id == "data") {
                val n = minOf(len, b.limit() - pos - 8) / 2
                return FloatArray(n) { b.getShort(pos + 8 + it * 2) / 32767f } to rate
            }
            pos += 8 + len + (len and 1)
        }
        return FloatArray(0) to rate
    }

    /** Scale down so the peak is at most [limit]; quieter audio is left alone (blue_onnx.limit_peak). */
    fun limitPeak(a: FloatArray, limit: Float = 0.95f): FloatArray {
        var peak = 0f
        for (v in a) { if (!v.isFinite()) return a; peak = maxOf(peak, abs(v)) }
        if (peak <= limit || peak < 1e-9f) return a
        val k = limit / peak
        return FloatArray(a.size) { a[it] * k }
    }
}
