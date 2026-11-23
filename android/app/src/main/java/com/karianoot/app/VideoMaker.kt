package com.karianoot.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.view.Surface
import androidx.core.content.res.ResourcesCompat
import com.karianoot.engine.Captions
import com.karianoot.engine.Wav
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A vertical video (1080x1920, 30 fps, H.264 + AAC in MP4) of a narration for TikTok / Reels / Shorts:
 * the words on screen as they are said (the current one in gold), over the user's pictures (slowly zooming, cross-faded)
 * or a dark gold background, with gold bars that move with the voice and an optional title on top.
 * Frames are drawn with a Canvas into a bitmap, uploaded to the encoder's input surface with OpenGL, and stamped with
 * their exact time (eglPresentationTimeANDROID), so the picture never drifts from the sound.
 */
class VideoMaker(private val ctx: Context) {
    data class Style(val title: String = "", val images: List<Uri> = emptyList(), val karaoke: Boolean = true)

    companion object {
        const val FPS = 30
        private const val TAIL = 0.6f
        private val GOLD = Color.rgb(0xE3, 0xBF, 0x6A)
        private val GOLD_LIGHT = Color.rgb(0xF8, 0xE2, 0xA4)
    }

    /** Writes [out]; [onProgress] 0..1; stops (and throws) when [cancelled]. Returns the video's size actually used. */
    fun make(wav: File, cards: List<Captions.Card>, style: Style, out: File, onProgress: (Float) -> Unit = {},
             cancelled: () -> Boolean = { false }): Pair<Int, Int> {
        val (audio, rate) = Wav.read(wav)
        val seconds = audio.size.toFloat() / rate + TAIL
        val frames = (seconds * FPS).toInt().coerceAtLeast(1)
        val aac = encodeAudio(audio, rate)
        // Some encoders (older or software ones) refuse 1080x1920; 720x1280 is accepted everywhere.
        var lastError: Throwable? = null
        for ((w, h) in listOf(1080 to 1920, 720 to 1280)) {
            try {
                encodeVideo(w, h, frames, audio, rate, cards, style, aac, out, onProgress, cancelled)
                return w to h
            } catch (e: kotlinx.coroutines.CancellationException) {
                out.delete(); throw e
            } catch (e: Exception) {
                lastError = e; out.delete()
            }
        }
        throw lastError ?: IllegalStateException("video")
    }

    private class Encoded(val format: MediaFormat, val samples: List<Pair<ByteArray, MediaCodec.BufferInfo>>)

    private fun encodeAudio(x: FloatArray, rate: Int): Encoded {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val out = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>()
        var outFormat: MediaFormat? = null
        val info = MediaCodec.BufferInfo()
        // The tail of the video is silence.
        val total = x.size + (TAIL * rate).toInt()
        var pos = 0; var inDone = false; var outDone = false
        try {
            while (!outDone) {
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        buf.clear()
                        val n = min(total - pos, min(1024, buf.remaining() / 2))
                        if (n <= 0) {
                            codec.queueInputBuffer(i, 0, 0, pos * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                        } else {
                            buf.order(ByteOrder.LITTLE_ENDIAN)
                            for (k in 0 until n) {
                                val v = if (pos + k < x.size) x[pos + k] else 0f
                                buf.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                            }
                            codec.queueInputBuffer(i, 0, n * 2, pos * 1_000_000L / rate, 0)
                            pos += n
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = codec.outputFormat
                    o >= 0 -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            val b = codec.getOutputBuffer(o)!!
                            b.position(info.offset); b.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size); b.get(bytes)
                            out.add(bytes to MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) })
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                        codec.releaseOutputBuffer(o, false)
                    }
                }
            }
        } finally { codec.stop(); codec.release() }
        return Encoded(outFormat ?: error("no audio format"), out)
    }

    private fun encodeVideo(w: Int, h: Int, frames: Int, audio: FloatArray, rate: Int, cards: List<Captions.Card>, style: Style,
                            aac: Encoded, out: File, onProgress: (Float) -> Unit, cancelled: () -> Boolean) {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, if (w >= 1080) 8_000_000 else 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrack = -1
        var started = false
        val gl = Gl(surface, w, h)
        val painter = Painter(w, h, audio, rate, cards, style)
        val info = MediaCodec.BufferInfo()
        fun drain(end: Boolean) {
            while (true) {
                val o = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                if (o == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!end) return else continue }
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoTrack = muxer.addTrack(codec.outputFormat)
                    val audioTrack = muxer.addTrack(aac.format)
                    muxer.start(); started = true
                    for ((bytes, bi) in aac.samples) muxer.writeSampleData(audioTrack, ByteBuffer.wrap(bytes), bi)
                    continue
                }
                if (o < 0) continue
                val b = codec.getOutputBuffer(o)!!
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0 && started) { b.position(info.offset); b.limit(info.offset + info.size); muxer.writeSampleData(videoTrack, b, info) }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }
        try {
            for (f in 0 until frames) {
                if (cancelled()) throw kotlinx.coroutines.CancellationException("cancelled")
                val t = f.toFloat() / FPS
                gl.draw(painter.frame(t), f * 1_000_000_000L / FPS)
                drain(false)
                if (f % 15 == 0) onProgress(f.toFloat() / frames)
            }
            codec.signalEndOfInputStream()
            drain(true)
            onProgress(1f)
        } finally {
            runCatching { codec.stop() }; codec.release()
            gl.release(); surface.release(); painter.release()
            runCatching { if (started) muxer.stop() }; muxer.release()
        }
        if (!started) error("the encoder gave no video")
    }

    /** Draws one frame of the video into a bitmap. */
    private inner class Painter(val w: Int, val h: Int, val audio: FloatArray, val rate: Int, val cards: List<Captions.Card>, val style: Style) {
        val bmp: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val k = w / 1080f
        val font: Typeface = runCatching { Typeface.create(ResourcesCompat.getFont(ctx, R.font.rubik), 800, false) }.getOrDefault(Typeface.DEFAULT_BOLD)
        val images: List<Bitmap> = style.images.mapNotNull { load(it) }
        val seconds = audio.size.toFloat() / rate + TAIL
        val fill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font; textSize = 84 * k; color = Color.WHITE; setShadowLayer(18 * k, 0f, 6 * k, Color.argb(200, 0, 0, 0)) }
        val stroke = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font; textSize = 84 * k; color = Color.argb(235, 10, 8, 4); setStyle(Paint.Style.STROKE); strokeWidth = 14 * k; strokeJoin = Paint.Join.ROUND }
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font; textSize = 76 * k; setShadowLayer(14 * k, 0f, 4 * k, Color.BLACK) }
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val shade = Paint().apply { shader = LinearGradient(0f, h * 0.35f, 0f, h.toFloat(), Color.TRANSPARENT, Color.argb(215, 0, 0, 0), Shader.TileMode.CLAMP) }
        val topShade = Paint().apply { shader = LinearGradient(0f, 0f, 0f, h * 0.25f, Color.argb(170, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP) }
        var cached: Triple<Int, Int, Pair<StaticLayout, StaticLayout>>? = null
        val titleLayout: StaticLayout? = style.title.trim().takeIf { it.isNotEmpty() }?.let { t ->
            titlePaint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, intArrayOf(GOLD_LIGHT, GOLD, GOLD_LIGHT), null, Shader.TileMode.CLAMP)
            layout(t, titlePaint, (w * 0.86f).toInt())
        }
        /** Voice loudness per video frame, smoothed, 0..1 (for the bars). */
        val level: FloatArray = run {
            val n = (seconds * FPS).toInt() + 1
            val hop = rate / FPS
            val raw = FloatArray(n) { f -> var s = 0.0; val a = f * hop; val b = min(audio.size, a + hop); for (i in a until b) s += audio[i] * audio[i]; if (b > a) sqrt(s / (b - a)).toFloat() else 0f }
            val peak = raw.maxOrNull()?.takeIf { it > 1e-6f } ?: 1f
            var g = 0f
            FloatArray(n) { f -> g = max(raw[f] / peak, g * 0.82f); g }
        }

        fun load(u: Uri): Bitmap? = runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
            var s = 1; while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) s *= 2
            ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
        }.getOrNull()

        fun layout(text: CharSequence, paint: TextPaint, width: Int): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL).setLineSpacing(0f, 1.05f).setIncludePad(false).build()

        fun frame(t: Float): Bitmap {
            background(t)
            titleLayout?.let { l ->
                c.drawRect(0f, 0f, w.toFloat(), h * 0.25f, topShade)
                c.save(); c.translate((w - l.width) / 2f, 150 * k); l.draw(c); c.restore()
            }
            caption(t)
            bars(t)
            // Progress line at the very bottom.
            p.shader = null; p.color = GOLD; p.alpha = 220
            c.drawRect(0f, h - 10 * k, w * (t / seconds).coerceIn(0f, 1f), h.toFloat(), p)
            p.alpha = 255
            return bmp
        }

        fun background(t: Float) {
            if (images.isEmpty()) {
                c.drawColor(Color.rgb(7, 6, 5))
                // Two soft gold glows drifting slowly.
                for ((i, ph) in floatArrayOf(0f, 2.1f).withIndex()) {
                    val cx = w * (0.5f + 0.32f * sin(t * 0.21f + ph)); val cy = h * (0.3f + 0.4f * i + 0.08f * sin(t * 0.17f + ph))
                    p.shader = RadialGradient(cx, cy, w * 0.9f, Color.argb(if (i == 0) 70 else 45, 0xE3, 0xBF, 0x6A), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
                }
                p.shader = null
                return
            }
            val per = seconds / images.size
            val i = min(images.size - 1, (t / per).toInt())
            drawKenBurns(images[i], (t - i * per) / per, i, 255)
            // Cross-fade into the next picture over the last 0.5 s of this one.
            val left = (i + 1) * per - t
            if (i + 1 < images.size && left < 0.5f) drawKenBurns(images[i + 1], 0f, i + 1, ((0.5f - left) / 0.5f * 255).toInt())
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), shade)
        }

        /** The picture filling the frame (cropped), zooming from 1.0 to 1.12 and drifting a little. */
        fun drawKenBurns(b: Bitmap, u: Float, index: Int, alpha: Int) {
            val scale = max(w.toFloat() / b.width, h.toFloat() / b.height) * (1f + 0.12f * u)
            val dx = (if (index % 2 == 0) -1 else 1) * 0.03f * w * u
            val m = Matrix()
            m.postScale(scale, scale)
            m.postTranslate((w - b.width * scale) / 2f + dx, (h - b.height * scale) / 2f)
            p.alpha = alpha
            c.drawBitmap(b, m, p)
            p.alpha = 255
        }

        fun caption(t: Float) {
            val idx = cards.indexOfLast { it.start - 0.08f <= t }
            if (idx < 0) return
            val card = cards[idx]
            val until = cards.getOrNull(idx + 1)?.start?.minus(0.08f) ?: (card.end + 0.8f)
            if (t > max(until, card.end + 0.05f) && idx == cards.size - 1) return
            val word = if (style.karaoke) card.words.indexOfLast { it.start <= t }.coerceAtLeast(0) else -1
            val pair = cached?.takeIf { it.first == idx && it.second == word }?.third ?: run {
                val text = card.text
                val sp = SpannableString(text)
                if (word >= 0) {
                    var at = 0
                    card.words.forEachIndexed { j, wd ->
                        val s = text.indexOf(wd.text, at); if (s < 0) return@forEachIndexed
                        if (j == word) sp.setSpan(ForegroundColorSpan(GOLD_LIGHT), s, s + wd.text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        at = s + wd.text.length
                    }
                }
                val width = (w * 0.86f).toInt()
                (layout(text, stroke, width) to layout(sp, fill, width)).also { cached = Triple(idx, word, it) }
            }
            // A small pop when a new card appears.
            val age = t - (card.start - 0.08f)
            val pop = if (age < 0.14f) 0.9f + 0.1f * (age / 0.14f) else 1f
            val cy = h * 0.64f
            c.save()
            c.translate(w / 2f, cy); c.scale(pop, pop); c.translate(-pair.first.width / 2f, -pair.first.height / 2f)
            pair.first.draw(c); pair.second.draw(c)
            c.restore()
        }

        fun bars(t: Float) {
            val f = (t * FPS).toInt().coerceIn(0, level.size - 1)
            val n = 23; val bw = 18 * k; val gap = 12 * k
            val total = n * bw + (n - 1) * gap
            val x0 = (w - total) / 2f; val base = h - 120 * k
            p.shader = LinearGradient(0f, base - 120 * k, 0f, base, GOLD_LIGHT, GOLD, Shader.TileMode.CLAMP)
            for (i in 0 until n) {
                val shape = 0.35f + 0.65f * sin(PI.toFloat() * (i + 0.5f) / n)
                val wob = 0.75f + 0.25f * sin(t * 9f + i * 1.7f)
                val bh = (10 * k + 110 * k * level[f] * shape * wob)
                val x = x0 + i * (bw + gap)
                c.drawRoundRect(RectF(x, base - bh, x + bw, base), bw / 2, bw / 2, p)
            }
            p.shader = null
        }

        fun release() { bmp.recycle(); images.forEach { it.recycle() } }
    }

    /** OpenGL ES 2 on the encoder's input surface: one textured quad per frame, stamped with its time. */
    private class Gl(surface: Surface, val w: Int, val h: Int) {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: EGLContext
        private val eglSurface: EGLSurface
        private val program: Int
        private val tex: Int
        private var uploaded = false
        private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            // x, y, u, v (v flipped: bitmaps are top-down)
            put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)); position(0)
        }

        init {
            val v = IntArray(2)
            check(EGL14.eglInitialize(display, v, 0, v, 1)) { "eglInitialize" }
            val attrs = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE)
            val configs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
            check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "eglChooseConfig" }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent" }
            program = link(
                "attribute vec2 p; attribute vec2 t; varying vec2 vt; void main(){ vt=t; gl_Position=vec4(p,0.0,1.0); }",
                "precision mediump float; varying vec2 vt; uniform sampler2D s; void main(){ gl_FragColor=texture2D(s, vt); }")
            val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0); tex = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glViewport(0, 0, w, h)
        }

        private fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type); GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
            val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }

        private fun link(vs: String, fs: String): Int {
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs)); GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
            val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "link: " + GLES20.glGetProgramInfoLog(p) }
            return p
        }

        fun draw(b: Bitmap, ptsNs: Long) {
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            if (!uploaded) { GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0); uploaded = true } else GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, b)
            val pl = GLES20.glGetAttribLocation(program, "p"); val tl = GLES20.glGetAttribLocation(program, "t")
            quad.position(0); GLES20.glVertexAttribPointer(pl, 2, GLES20.GL_FLOAT, false, 16, quad); GLES20.glEnableVertexAttribArray(pl)
            quad.position(2); GLES20.glVertexAttribPointer(tl, 2, GLES20.GL_FLOAT, false, 16, quad); GLES20.glEnableVertexAttribArray(tl)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "s"), 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, ptsNs)
            EGL14.eglSwapBuffers(display, eglSurface)
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, eglSurface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }

        companion object { const val EGL_RECORDABLE_ANDROID = 0x3142 }
    }
}
