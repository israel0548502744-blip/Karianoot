package com.karianoot.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import com.googlecode.tesseract.android.TessBaseAPI
import com.karianoot.engine.SlipText
import java.io.File

/**
 * "צילום טופס": the text of a slip screenshot, read on the phone by Tesseract (Hebrew + English, tessdata_fast).
 * Tested on rendered slips with the same two-pass recipe on the CLI (ocr-probe.yml, tools/release/slip_merge.py).
 * The picture is made grey, enlarged when small, and inverted when the app is dark (Tesseract reads dark on light).
 */
object SlipReader {
    fun read(ctx: Context, uri: Uri, modelDir: File): String {
        val src = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: error("לא הצלחתי לפתוח את התמונה")
        val prepared = prepare(src)
        if (prepared !== src) src.recycle()
        val heb = TessBaseAPI()
        val dig = TessBaseAPI()
        try {
            // The Hebrew pass reads the words (its digits come out as letters); every word that is not Hebrew is read again,
            // digits only, from its own box (SlipText.text). The same recipe is tested on the CLI in ocr-probe.yml.
            check(heb.init(modelDir.path, "heb") && dig.init(modelDir.path, "eng")) { "מנוע זיהוי הטקסט לא נטען" }
            heb.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
            heb.setImage(prepared)
            val words = SlipText.hocrWords(heb.getHOCRText(0) ?: "")
            dig.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_LINE)
            dig.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, "0123456789.,X")
            dig.setImage(prepared)
            return SlipText.text(words) { w ->
                val pad = maxOf(4, w.h / 4)
                dig.setRectangle(maxOf(0, w.x - pad), maxOf(0, w.y - pad), minOf(prepared.width - maxOf(0, w.x - pad), w.w + 2 * pad),
                    minOf(prepared.height - maxOf(0, w.y - pad), w.h + 2 * pad))
                dig.getUTF8Text()
            }
        } finally {
            heb.recycle(); dig.recycle()
            prepared.recycle()
        }
    }

    private fun prepare(b: Bitmap): Bitmap {
        val scale = when { b.width < 900 -> 2f; b.width > 2400 -> 2400f / b.width; else -> 1f }
        val w = (b.width * scale).toInt(); val h = (b.height * scale).toInt()
        // Mean brightness on a coarse grid: a dark app screenshot gets inverted.
        var sum = 0.0; var n = 0
        for (y in 0 until b.height step maxOf(1, b.height / 40)) for (x in 0 until b.width step maxOf(1, b.width / 40)) {
            val c = b.getPixel(x, y); sum += (0.299 * ((c shr 16) and 255) + 0.587 * ((c shr 8) and 255) + 0.114 * (c and 255)) / 255; n++
        }
        val dark = n > 0 && sum / n < 0.45
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val m = ColorMatrix().apply { setSaturation(0f) }
        if (dark) m.postConcat(ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))
        Canvas(out).drawBitmap(b, null, android.graphics.Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(m) })
        return out
    }
}
