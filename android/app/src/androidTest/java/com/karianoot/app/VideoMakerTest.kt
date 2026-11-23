package com.karianoot.app

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.karianoot.engine.Captions
import com.karianoot.engine.Wav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * The video maker on a real Android (the emulator in video-test.yml): an MP4 with an H.264 and an AAC track, as long as
 * the narration, with the caption drawn. Frames are saved to files/videotest/ for a look.
 */
@RunWith(AndroidJUnit4::class)
class VideoMakerTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.getExternalFilesDir(null), "videotest").apply { mkdirs() }

    private fun narration(): Pair<File, List<Captions.Card>> {
        val rate = 22050
        // "ערב טוב לכל האוהדים!" 0.2-1.4 s, pause, "הנה הטופס של היום." 2.0-3.4 s
        val a = FloatArray((3.6 * rate).toInt()) { i -> val t = i.toFloat() / rate
            if (t in 0.2f..1.4f || t in 2.0f..3.4f) (0.4 * sin(2 * PI * 170 * t) * (0.6 + 0.4 * sin(2 * PI * 4 * t))).toFloat() else 0f }
        val f = File(dir, "voice.wav"); Wav.write(f, a, rate)
        val words = Captions.words(listOf(Captions.Span("ערב טוב לכל האוהדים!", (0.2 * rate).toInt(), (1.4 * rate).toInt()),
            Captions.Span("הנה הטופס של היום.", (2.0 * rate).toInt(), (3.4 * rate).toInt())), a, rate)
        return f to Captions.cards(words)
    }

    private fun check(mp4: File, seconds: Double) {
        val ex = MediaExtractor(); ex.setDataSource(mp4.path)
        val mimes = (0 until ex.trackCount).map { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
        println("CHECK tracks $mimes size ${mp4.length()}")
        assertTrue(mimes.contains("video/avc")); assertTrue(mimes.contains("audio/mp4a-latm"))
        ex.release()
        val r = MediaMetadataRetriever(); r.setDataSource(mp4.path)
        val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toDouble() / 1000
        println("CHECK duration $dur expected $seconds")
        assertEquals(seconds, dur, 0.35)
        for ((name, t) in listOf("early" to 0.5, "word" to 1.0, "second" to 2.6)) {
            val b = r.getFrameAtTime((t * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)!!
            File(dir, mp4.nameWithoutExtension + "_$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) }
            // The caption is drawn: bright pixels in the caption band.
            var bright = 0
            for (y in (b.height * 0.55).toInt() until (b.height * 0.72).toInt() step 4) for (x in 0 until b.width step 4)
                if (Color.luminance(b.getPixel(x, y)) > 0.7f) bright++
            println("CHECK $name bright $bright")
            assertTrue("caption at $t s", bright > 50)
        }
        r.release()
    }

    @Test fun plainBackground() {
        val (wav, cards) = narration()
        val out = File(dir, "plain.mp4")
        val size = VideoMaker(ctx).make(wav, cards, VideoMaker.Style(title = "יום המזל"), out)
        println("CHECK size $size")
        check(out, 3.6 + 0.6)
    }

    @Test fun withPictures() {
        val (wav, cards) = narration()
        val pics = listOf(Color.rgb(20, 90, 40), Color.rgb(30, 40, 120)).mapIndexed { i, col ->
            val b = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888); b.eraseColor(col)
            val f = File(dir, "pic$i.png"); f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) }; Uri.fromFile(f)
        }
        val out = File(dir, "pictures.mp4")
        VideoMaker(ctx).make(wav, cards, VideoMaker.Style(images = pics), out)
        check(out, 3.6 + 0.6)
    }
}
