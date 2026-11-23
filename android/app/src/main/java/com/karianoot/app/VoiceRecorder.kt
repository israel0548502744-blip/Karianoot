package com.karianoot.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.karianoot.engine.Wav
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/** Records the user's voice (44.1 kHz mono) for "קול חדש מהקלטה"; nothing leaves the phone. */
class VoiceRecorder(private val scope: CoroutineScope) {
    companion object { const val RATE = 44100; const val MAX_SECONDS = 30 }

    /** (seconds recorded, current level 0..1) while recording. */
    val state = MutableStateFlow<Pair<Float, Float>?>(null)
    private var job: Job? = null
    private var samples = FloatArray(0)
    private var n = 0

    @SuppressLint("MissingPermission")   // asked for in the dialog before start()
    fun start() {
        if (job != null) return
        samples = FloatArray(RATE * MAX_SECONDS); n = 0
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192))
        state.value = 0f to 0f
        job = scope.launch(Dispatchers.IO) {
            val buf = ShortArray(2048)
            rec.startRecording()
            try {
                while (isActive && n < samples.size) {
                    val k = rec.read(buf, 0, buf.size)
                    if (k <= 0) continue
                    var peak = 0f
                    for (i in 0 until minOf(k, samples.size - n)) { val v = buf[i] / 32768f; samples[n + i] = v; peak = maxOf(peak, abs(v)) }
                    n = minOf(samples.size, n + k)
                    state.value = n.toFloat() / RATE to peak
                }
            } finally { rec.stop(); rec.release() }
        }
    }

    /** Stops and writes the recording to [out] (WAV); returns its length in seconds. */
    suspend fun stop(out: File): Float {
        job?.let { it.cancel(); it.join() }
        job = null
        state.value = null
        Wav.write(out, samples.copyOf(n), RATE)
        return n.toFloat() / RATE
    }

    fun cancel() { job?.cancel(); job = null; state.value = null }
}
