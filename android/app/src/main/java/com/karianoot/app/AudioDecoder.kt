package com.karianoot.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.karianoot.engine.VoiceCloner
import java.nio.ByteOrder
import kotlin.math.abs

/** Any audio file the phone can play (mp3, m4a, wav, ogg…) -> mono float samples at 44.1 kHz. */
object AudioDecoder {
    fun decode(ctx: Context, uri: Uri, maxSeconds: Int = 30): FloatArray {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: error("לא נמצא קול בקובץ")
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val out = ArrayList<Float>(rate * maxSeconds)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone && out.size < rate * maxSeconds) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        codec.outputFormat.let { rate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                    }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val sh = buf.asShortBuffer()
                        val frames = sh.remaining() / channels
                        for (f in 0 until frames) {
                            var acc = 0f
                            for (c in 0 until channels) acc += sh.get(f * channels + c) / 32768f
                            out.add(acc / channels)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            codec.stop(); codec.release(); ex.release()
        }
        val pcm = trimLeadingSilence(out.toFloatArray())
        return VoiceCloner.resample(pcm, rate)
    }

    /** Skips quiet audio at the start so the voice is learned from speech. */
    private fun trimLeadingSilence(x: FloatArray): FloatArray {
        val peak = x.maxOfOrNull { abs(it) } ?: return x
        val gate = peak * 0.05f
        val start = x.indexOfFirst { abs(it) > gate }.coerceAtLeast(0)
        return x.copyOfRange(maxOf(0, start - 2205), x.size)
    }
}
