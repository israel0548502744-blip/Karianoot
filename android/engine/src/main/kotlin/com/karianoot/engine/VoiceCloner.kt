package com.karianoot.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Makes a Blue 2.5 voice from a recording, on ONNX only (measured in tools/clone_probe.py):
 *   wav (44.1 kHz mono) -> log linear+mel spectrogram [1, 1253, T]   (blue_onnx.style)
 *   -> Blue v2 codec_encoder -> reference latents z_ref [1, 144, T']
 *   -> Blue 2.5 reference_encoder -> style_ttl [1, 50, 256].
 * Duration then comes from Blue 2.5's duration_predictor, which reads z_ref directly.
 */
class VoiceCloner(codecEncoder: File, referenceEncoder: File,
                  private val env: OrtEnvironment = OrtEnvironment.getEnvironment()) : AutoCloseable {
    private val codec = env.createSession(codecEncoder.path, OrtSession.SessionOptions())
    private val refEnc = env.createSession(referenceEncoder.path, OrtSession.SessionOptions())

    /** Returns the voice, or throws when the recording is too short or silent. */
    fun clone(wav44k: FloatArray): BlueVoice {
        require(wav44k.size > SR * 3) { "ההקלטה קצרה מדי (צריך לפחות 3 שניות של דיבור)" }
        val mel = features(wav44k)
        val frames = (mel.shape[2] / COMPRESS) * COMPRESS
        val melT = cropTime(mel, frames)
        val z = run1(codec, mapOf(codec.inputNames.first() to melT))
        val zt = trimReference(z)
        val mask = NpArray(intArrayOf(1, 1, zt.shape[2]), FloatArray(zt.shape[2]) { 1f })
        val names = refEnc.inputNames.toList()
        val ttl = run1(refEnc, mapOf(names[0] to zt, names[1] to mask))
        return BlueVoice(ttl, NpArray(intArrayOf(1, 8, 16), FloatArray(128)), zRef = zt)
    }

    private fun run1(s: OrtSession, inputs: Map<String, NpArray>): NpArray {
        val ts = inputs.mapValues { (_, a) -> OnnxTensor.createTensor(env, FloatBuffer.wrap(a.data), a.shape.map { it.toLong() }.toLongArray()) }
        try {
            s.run(ts).use { r ->
                val t = r.get(0) as OnnxTensor
                val b = t.floatBuffer
                return NpArray(t.info.shape.map { it.toInt() }.toIntArray(), FloatArray(b.remaining()).also { b.get(it) })
            }
        } finally { ts.values.forEach { it.close() } }
    }

    override fun close() { codec.close(); refEnc.close() }

    companion object {
        const val SR = 44100
        const val N_FFT = 2048
        const val HOP = 512
        const val N_MELS = 228
        const val COMPRESS = 6
        const val MAX_FRAMES = 150

        /** `blue_onnx.style._linear_mel_features`: log |STFT| (1025) stacked on log mel (228, HTK, unnormalized). */
        fun features(wav: FloatArray): NpArray {
            val pad = N_FFT / 2
            val n = wav.size
            val padded = FloatArray(n + 2 * pad) { i ->
                var j = i - pad
                // numpy "reflect" padding (edge sample not repeated)
                while (j < 0 || j >= n) { if (j < 0) j = -j; if (j >= n) j = 2 * (n - 1) - j }
                wav[j]
            }
            val frames = 1 + (padded.size - N_FFT) / HOP
            val bins = N_FFT / 2 + 1
            val window = FloatArray(N_FFT) { (0.5 - 0.5 * cos(2 * PI * it / N_FFT)).toFloat() }
            val basis = melBasis()
            val out = FloatArray((bins + N_MELS) * frames)
            val re = DoubleArray(N_FFT); val im = DoubleArray(N_FFT)
            val mag = FloatArray(bins)
            for (f in 0 until frames) {
                val off = f * HOP
                for (k in 0 until N_FFT) { re[k] = (padded[off + k] * window[k]).toDouble(); im[k] = 0.0 }
                fft(re, im)
                for (k in 0 until bins) mag[k] = sqrt(re[k] * re[k] + im[k] * im[k]).toFloat()
                for (k in 0 until bins) out[k * frames + f] = ln(max(mag[k], 1e-5f))
                for (m in 0 until N_MELS) {
                    var acc = 0f
                    val row = basis[m]
                    for (k in 0 until bins) acc += row[k] * mag[k]
                    out[(bins + m) * frames + f] = ln(max(acc, 1e-5f))
                }
            }
            return NpArray(intArrayOf(1, bins + N_MELS, frames), out)
        }

        /** librosa.filters.mel(sr=44100, n_fft=2048, n_mels=228, fmin=0, fmax=None, htk=True, norm=None). */
        fun melBasis(): Array<FloatArray> {
            val bins = N_FFT / 2 + 1
            val fft = DoubleArray(bins) { it * (SR / 2.0) / (bins - 1) }
            fun hzToMel(h: Double) = 2595.0 * log10(1.0 + h / 700.0)
            fun melToHz(m: Double) = 700.0 * (10.0.pow(m / 2595.0) - 1.0)
            val lo = hzToMel(0.0); val hi = hzToMel(SR / 2.0)
            val melF = DoubleArray(N_MELS + 2) { melToHz(lo + (hi - lo) * it / (N_MELS + 1)) }
            return Array(N_MELS) { i ->
                val d0 = melF[i + 1] - melF[i]; val d1 = melF[i + 2] - melF[i + 1]
                FloatArray(bins) { k ->
                    val lower = -(melF[i] - fft[k]) / d0
                    val upper = (melF[i + 2] - fft[k]) / d1
                    max(0.0, min(lower, upper)).toFloat()
                }
            }
        }

        /** `_trim_reference_latents`: drop the last 5% (at least 2 frames), keep at most 150. */
        fun trimReference(z: NpArray): NpArray {
            val t = z.shape[2]
            val tail = max(2, (t * 0.05).toInt())
            var keep = max(1, t - tail)
            keep = min(keep, MAX_FRAMES)
            return cropTime(z, keep)
        }

        fun cropTime(a: NpArray, t: Int): NpArray {
            val c = a.shape[1]; val old = a.shape[2]
            if (t == old) return a
            val out = FloatArray(c * t)
            for (ch in 0 until c) System.arraycopy(a.data, ch * old, out, ch * t, t)
            return NpArray(intArrayOf(a.shape[0], c, t), out)
        }

        /** In-place radix-2 FFT. */
        fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
            }
            var len = 2
            while (len <= n) {
                val ang = -2 * PI / len
                val wr = cos(ang); val wi = sin(ang)
                var i = 0
                while (i < n) {
                    var cr = 1.0; var ci = 0.0
                    for (k in 0 until len / 2) {
                        val ur = re[i + k]; val ui = im[i + k]
                        val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                        val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                        re[i + k] = ur + vr; im[i + k] = ui + vi
                        re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                        val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                    }
                    i += len
                }
                len = len shl 1
            }
        }

        /** Windowed-sinc resampling to 44.1 kHz (for recordings at other rates). */
        fun resample(x: FloatArray, from: Int, to: Int = SR): FloatArray {
            if (from == to) return x
            val ratio = to.toDouble() / from
            val n = (x.size * ratio).toInt()
            val cutoff = min(1.0, ratio)
            val half = 32
            return FloatArray(n) { i ->
                val pos = i / ratio
                val c = pos.toInt()
                var acc = 0.0; var wsum = 0.0
                for (k in c - half + 1..c + half) {
                    if (k < 0 || k >= x.size) continue
                    val d = pos - k
                    val sinc = if (d == 0.0) 1.0 else sin(PI * d * cutoff) / (PI * d * cutoff)
                    val w = 0.5 + 0.5 * cos(PI * d / half)
                    val h = sinc * w * cutoff
                    acc += x[k] * h; wsum += h
                }
                (if (wsum != 0.0) acc / wsum else 0.0).toFloat()
            }
        }
    }
}
